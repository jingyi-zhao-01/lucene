/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.codecs.lucene99;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.hnsw.FlatVectorsReader;
import org.apache.lucene.codecs.hnsw.HnswGraphProvider;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.internal.hppc.IntObjectHashMap;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.DataAccessHint;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.FileDataHint;
import org.apache.lucene.store.FileTypeHint;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.PreloadHint;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IOSupplier;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.HnswGraphSearcher;
import org.apache.lucene.util.hnsw.OrdinalTranslatedKnnCollector;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.packed.DirectMonotonicReader;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues;
import org.apache.lucene.util.quantization.QuantizedVectorsReader;
import org.apache.lucene.util.quantization.ScalarQuantizer;

/**
 * Reads vectors from the index segments along with index data structures supporting KNN search.
 *
 * @lucene.experimental
 */
public final class Lucene99HnswVectorsReader extends KnnVectorsReader
    implements QuantizedVectorsReader, HnswGraphProvider {

  private static final long SHALLOW_SIZE =
      RamUsageEstimator.shallowSizeOfInstance(Lucene99HnswVectorsFormat.class);
  // Number of ordinals to score at a time when scoring exhaustively rather than using HNSW.
  private static final int EXHAUSTIVE_BULK_SCORE_ORDS = 64;

  private final FlatVectorsReader flatVectorsReader;
  private final FieldInfos fieldInfos;
  private final IntObjectHashMap<FieldEntry> fields;
  private final IndexInput vectorIndex;

  public Lucene99HnswVectorsReader(SegmentReadState state, FlatVectorsReader flatVectorsReader)
      throws IOException {
    this.fields = new IntObjectHashMap<>();
    this.flatVectorsReader = flatVectorsReader;
    this.fieldInfos = state.fieldInfos;
    String metaFileName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name, state.segmentSuffix, Lucene99HnswVectorsFormat.META_EXTENSION);
    int versionMeta = -1;
    try (ChecksumIndexInput meta = state.directory.openChecksumInput(metaFileName)) {
      Throwable priorE = null;
      try {
        versionMeta =
            CodecUtil.checkIndexHeader(
                meta,
                Lucene99HnswVectorsFormat.META_CODEC_NAME,
                Lucene99HnswVectorsFormat.VERSION_START,
                Lucene99HnswVectorsFormat.VERSION_CURRENT,
                state.segmentInfo.getId(),
                state.segmentSuffix);
        readFields(meta);
      } catch (Throwable exception) {
        priorE = exception;
      } finally {
        CodecUtil.checkFooter(meta, priorE);
      }
      this.vectorIndex =
          openDataInput(
              state,
              versionMeta,
              Lucene99HnswVectorsFormat.VECTOR_INDEX_EXTENSION,
              Lucene99HnswVectorsFormat.VECTOR_INDEX_CODEC_NAME,
              state.context.withHints(
                  // Even though this input is referred to an `indexIn`, it doesn't qualify as
                  // FileTypeHint#INDEX since it's a large file
                  FileTypeHint.DATA,
                  FileDataHint.KNN_VECTORS,
                  DataAccessHint.RANDOM,
                  PreloadHint.INSTANCE));
    } catch (Throwable t) {
      IOUtils.closeWhileSuppressingExceptions(t, this);
      throw t;
    }
  }

  private Lucene99HnswVectorsReader(
      Lucene99HnswVectorsReader reader, FlatVectorsReader flatVectorsReader) {
    this.flatVectorsReader = flatVectorsReader;
    this.fieldInfos = reader.fieldInfos;
    this.fields = reader.fields;
    this.vectorIndex = reader.vectorIndex;
  }

  @Override
  public KnnVectorsReader getMergeInstance() {
    return new Lucene99HnswVectorsReader(this, this.flatVectorsReader.getMergeInstance());
  }

  @Override
  public void finishMerge() throws IOException {
    flatVectorsReader.finishMerge();
  }

  private static IndexInput openDataInput(
      SegmentReadState state,
      int versionMeta,
      String fileExtension,
      String codecName,
      IOContext context)
      throws IOException {
    String fileName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, fileExtension);
    IndexInput in = state.directory.openInput(fileName, context);
    try {
      int versionVectorData =
          CodecUtil.checkIndexHeader(
              in,
              codecName,
              Lucene99HnswVectorsFormat.VERSION_START,
              Lucene99HnswVectorsFormat.VERSION_CURRENT,
              state.segmentInfo.getId(),
              state.segmentSuffix);
      if (versionMeta != versionVectorData) {
        throw new CorruptIndexException(
            "Format versions mismatch: meta="
                + versionMeta
                + ", "
                + codecName
                + "="
                + versionVectorData,
            in);
      }
      CodecUtil.retrieveChecksum(in);
      return in;
    } catch (Throwable t) {
      IOUtils.closeWhileSuppressingExceptions(t, in);
      throw t;
    }
  }

  private void readFields(ChecksumIndexInput meta) throws IOException {
    for (int fieldNumber = meta.readInt(); fieldNumber != -1; fieldNumber = meta.readInt()) {
      FieldInfo info = fieldInfos.fieldInfo(fieldNumber);
      if (info == null) {
        throw new CorruptIndexException("Invalid field number: " + fieldNumber, meta);
      }
      FieldEntry fieldEntry = readField(meta, info);
      validateFieldEntry(info, fieldEntry);
      fields.put(info.number, fieldEntry);
    }
  }

  private void validateFieldEntry(FieldInfo info, FieldEntry fieldEntry) {
    int dimension = info.getVectorDimension();
    if (dimension != fieldEntry.dimension) {
      throw new IllegalStateException(
          "Inconsistent vector dimension for field=\""
              + info.name
              + "\"; "
              + dimension
              + " != "
              + fieldEntry.dimension);
    }
  }

  // List of vector similarity functions. This list is defined here, in order
  // to avoid an undesirable dependency on the declaration and order of values
  // in VectorSimilarityFunction. The list values and order must be identical
  // to that of {@link o.a.l.c.l.Lucene94FieldInfosFormat#SIMILARITY_FUNCTIONS}.
  public static final List<VectorSimilarityFunction> SIMILARITY_FUNCTIONS =
      List.of(
          VectorSimilarityFunction.EUCLIDEAN,
          VectorSimilarityFunction.DOT_PRODUCT,
          VectorSimilarityFunction.COSINE,
          VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT);

  public static VectorSimilarityFunction readSimilarityFunction(DataInput input)
      throws IOException {
    int i = input.readInt();
    if (i < 0 || i >= SIMILARITY_FUNCTIONS.size()) {
      throw new IllegalArgumentException("invalid distance function: " + i);
    }
    return SIMILARITY_FUNCTIONS.get(i);
  }

  public static VectorEncoding readVectorEncoding(DataInput input) throws IOException {
    int encodingId = input.readInt();
    if (encodingId < 0 || encodingId >= VectorEncoding.values().length) {
      throw new CorruptIndexException("Invalid vector encoding id: " + encodingId, input);
    }
    return VectorEncoding.values()[encodingId];
  }

  private FieldEntry readField(IndexInput input, FieldInfo info) throws IOException {
    VectorEncoding vectorEncoding = readVectorEncoding(input);
    VectorSimilarityFunction similarityFunction = readSimilarityFunction(input);
    if (similarityFunction != info.getVectorSimilarityFunction()) {
      throw new IllegalStateException(
          "Inconsistent vector similarity function for field=\""
              + info.name
              + "\"; "
              + similarityFunction
              + " != "
              + info.getVectorSimilarityFunction());
    }
    return FieldEntry.create(input, vectorEncoding, info.getVectorSimilarityFunction());
  }

  @Override
  public long ramBytesUsed() {
    return Lucene99HnswVectorsReader.SHALLOW_SIZE
        + fields.ramBytesUsed()
        + flatVectorsReader.ramBytesUsed();
  }

  @Override
  public void checkIntegrity() throws IOException {
    flatVectorsReader.checkIntegrity();
    CodecUtil.checksumEntireFile(vectorIndex);
  }

  @Override
  public FloatVectorValues getFloatVectorValues(String field) throws IOException {
    return flatVectorsReader.getFloatVectorValues(field);
  }

  @Override
  public ByteVectorValues getByteVectorValues(String field) throws IOException {
    return flatVectorsReader.getByteVectorValues(field);
  }

  private FieldEntry getFieldEntryOrThrow(String field) {
    final FieldInfo info = fieldInfos.fieldInfo(field);
    final FieldEntry entry;
    if (info == null || (entry = fields.get(info.number)) == null) {
      throw new IllegalArgumentException("field=\"" + field + "\" not found");
    }
    return entry;
  }

  private FieldEntry getFieldEntry(String field, VectorEncoding expectedEncoding) {
    final FieldEntry fieldEntry = getFieldEntryOrThrow(field);
    if (fieldEntry.vectorEncoding != expectedEncoding) {
      throw new IllegalArgumentException(
          "field=\""
              + field
              + "\" is encoded as: "
              + fieldEntry.vectorEncoding
              + " expected: "
              + expectedEncoding);
    }
    return fieldEntry;
  }

  @Override
  public void search(String field, float[] target, KnnCollector knnCollector, Bits acceptDocs)
      throws IOException {
    final FieldEntry fieldEntry = getFieldEntry(field, VectorEncoding.FLOAT32);
    search(
        fieldEntry,
        knnCollector,
        acceptDocs,
        () -> flatVectorsReader.getRandomVectorScorer(field, target));
  }

  @Override
  public void search(String field, byte[] target, KnnCollector knnCollector, Bits acceptDocs)
      throws IOException {
    final FieldEntry fieldEntry = getFieldEntry(field, VectorEncoding.BYTE);
    search(
        fieldEntry,
        knnCollector,
        acceptDocs,
        () -> flatVectorsReader.getRandomVectorScorer(field, target));
  }

  private void search(
      FieldEntry fieldEntry,
      KnnCollector knnCollector,
      Bits acceptDocs,
      IOSupplier<RandomVectorScorer> scorerSupplier)
      throws IOException {
    if (fieldEntry.size() == 0 || knnCollector.k() == 0) {
      return;
    }
    final RandomVectorScorer scorer = scorerSupplier.get();
    final KnnCollector collector =
        new OrdinalTranslatedKnnCollector(knnCollector, scorer::ordToDoc);
    final Bits acceptedOrds = scorer.getAcceptOrds(acceptDocs);
    HnswGraph graph = getGraph(fieldEntry);
    boolean doHnsw = knnCollector.k() < scorer.maxOrd();
    // Take into account if quantized? E.g. some scorer cost?
    int filteredDocCount = 0;
    // The approximate number of vectors that would be visited if we did not filter
    int unfilteredVisit = HnswGraphSearcher.expectedVisitedNodes(knnCollector.k(), graph.size());
    if (acceptDocs instanceof BitSet bitSet) {
      // Use approximate cardinality as this is good enough, but ensure we don't exceed the graph
      // size as that is illogical
      filteredDocCount = Math.min(bitSet.approximateCardinality(), graph.size());
      if (unfilteredVisit >= filteredDocCount) {
        doHnsw = false;
      }
    }
    if (doHnsw) {
      HnswGraphSearcher.search(
          scorer, collector, getGraph(fieldEntry), acceptedOrds, filteredDocCount);
    } else {
      // if k is larger than the number of vectors we expect to visit in an HNSW search,
      // we can just iterate over all vectors and collect them.
      int[] ords = new int[EXHAUSTIVE_BULK_SCORE_ORDS];
      float[] scores = new float[EXHAUSTIVE_BULK_SCORE_ORDS];
      int numOrds = 0;
      for (int i = 0; i < scorer.maxOrd(); i++) {
        if (acceptedOrds == null || acceptedOrds.get(i)) {
          if (knnCollector.earlyTerminated()) {
            break;
          }
          ords[numOrds++] = i;
          if (numOrds == ords.length) {
            scorer.bulkScore(ords, scores, numOrds);
            for (int j = 0; j < numOrds; j++) {
              knnCollector.incVisitedCount(1);
              knnCollector.collect(scorer.ordToDoc(ords[j]), scores[j]);
            }
            numOrds = 0;
          }
        }
      }

      if (numOrds > 0) {
        scorer.bulkScore(ords, scores, numOrds);
        for (int j = 0; j < numOrds; j++) {
          knnCollector.incVisitedCount(1);
          knnCollector.collect(scorer.ordToDoc(ords[j]), scores[j]);
        }
      }
    }
  }

  @Override
  public HnswGraph getGraph(String field) throws IOException {
    final FieldInfo info = fieldInfos.fieldInfo(field);
    final FieldEntry entry;
    if (info == null || (entry = fields.get(info.number)) == null) {
      throw new IllegalArgumentException("field=\"" + field + "\" not found");
    }
    if (entry.vectorIndexLength > 0) {
      return getGraph(entry);
    } else {
      return HnswGraph.EMPTY;
    }
  }

  private HnswGraph getGraph(FieldEntry entry) throws IOException {
    return new OffHeapHnswGraph(entry, vectorIndex);
  }

  @Override
  public Map<String, Long> getOffHeapByteSize(FieldInfo fieldInfo) {
    FieldEntry entry = getFieldEntryOrThrow(fieldInfo.name);
    var flat = flatVectorsReader.getOffHeapByteSize(fieldInfo);
    var graph = Map.of(Lucene99HnswVectorsFormat.VECTOR_INDEX_EXTENSION, entry.vectorIndexLength);
    return KnnVectorsReader.mergeOffHeapByteSizeMaps(flat, graph);
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(flatVectorsReader, vectorIndex);
  }

  @Override
  public QuantizedByteVectorValues getQuantizedVectorValues(String field) throws IOException {
    if (flatVectorsReader instanceof QuantizedVectorsReader) {
      return ((QuantizedVectorsReader) flatVectorsReader).getQuantizedVectorValues(field);
    }
    return null;
  }

  @Override
  public ScalarQuantizer getQuantizationState(String field) {
    if (flatVectorsReader instanceof QuantizedVectorsReader) {
      return ((QuantizedVectorsReader) flatVectorsReader).getQuantizationState(field);
    }
    return null;
  }

  private record FieldEntry(
      VectorSimilarityFunction similarityFunction,
      VectorEncoding vectorEncoding,
      long vectorIndexOffset,
      long vectorIndexLength,
      int M,
      int numLevels,
      int dimension,
      int size,
      int[][] nodesByLevel,
      // for each level the start offsets in vectorIndex file from where to read neighbours
      DirectMonotonicReader.Meta offsetsMeta,
      long offsetsOffset,
      int offsetsBlockShift,
      long offsetsLength) {

    static FieldEntry create(
        IndexInput input,
        VectorEncoding vectorEncoding,
        VectorSimilarityFunction similarityFunction)
        throws IOException {
      final var vectorIndexOffset = input.readVLong();
      final var vectorIndexLength = input.readVLong();
      final var dimension = input.readVInt();
      final var size = input.readInt();
      // read nodes by level
      final var M = input.readVInt();
      final var numLevels = input.readVInt();
      final var nodesByLevel = new int[numLevels][];
      long numberOfOffsets = 0;
      final long offsetsOffset;
      final int offsetsBlockShift;
      final DirectMonotonicReader.Meta offsetsMeta;
      final long offsetsLength;
      for (int level = 0; level < numLevels; level++) {
        if (level > 0) {
          int numNodesOnLevel = input.readVInt();
          numberOfOffsets += numNodesOnLevel;
          nodesByLevel[level] = new int[numNodesOnLevel];
          nodesByLevel[level][0] = input.readVInt();
          for (int i = 1; i < numNodesOnLevel; i++) {
            nodesByLevel[level][i] = nodesByLevel[level][i - 1] + input.readVInt();
          }
        } else {
          numberOfOffsets += size;
        }
      }
      if (numberOfOffsets > 0) {
        offsetsOffset = input.readLong();
        offsetsBlockShift = input.readVInt();
        offsetsMeta = DirectMonotonicReader.loadMeta(input, numberOfOffsets, offsetsBlockShift);
        offsetsLength = input.readLong();
      } else {
        offsetsOffset = 0;
        offsetsBlockShift = 0;
        offsetsMeta = null;
        offsetsLength = 0;
      }
      return new FieldEntry(
          similarityFunction,
          vectorEncoding,
          vectorIndexOffset,
          vectorIndexLength,
          M,
          numLevels,
          dimension,
          size,
          nodesByLevel,
          offsetsMeta,
          offsetsOffset,
          offsetsBlockShift,
          offsetsLength);
    }
  }

  /** Read the nearest-neighbors graph from the index input */
  private static final class OffHeapHnswGraph extends HnswGraph {

    final IndexInput dataIn;
    final int[][] nodesByLevel;
    final int numLevels;
    final int entryNode;
    final int size;
    int arcCount;
    int arcUpTo;
    int arc;
    private final int maxConn;
    private final DirectMonotonicReader graphLevelNodeOffsets;
    private final long[] graphLevelNodeIndexOffsets;
    // Allocated to be M*2 to track the current neighbors being explored
    private final int[] currentNeighborsBuffer;

    OffHeapHnswGraph(FieldEntry entry, IndexInput vectorIndex) throws IOException {
      this.dataIn =
          vectorIndex.slice("graph-data", entry.vectorIndexOffset, entry.vectorIndexLength);
      this.nodesByLevel = entry.nodesByLevel;
      this.numLevels = entry.numLevels;
      this.entryNode = numLevels > 1 ? nodesByLevel[numLevels - 1][0] : 0;
      this.size = entry.size();
      final RandomAccessInput addressesData =
          vectorIndex.randomAccessSlice(entry.offsetsOffset, entry.offsetsLength);
      this.graphLevelNodeOffsets =
          DirectMonotonicReader.getInstance(entry.offsetsMeta, addressesData);
      this.currentNeighborsBuffer = new int[entry.M * 2];
      this.maxConn = entry.M;
      graphLevelNodeIndexOffsets = new long[numLevels];
      graphLevelNodeIndexOffsets[0] = 0;
      for (int i = 1; i < numLevels; i++) {
        // nodesByLevel is `null` for the zeroth level as we know its size
        int nodeCount = nodesByLevel[i - 1] == null ? size : nodesByLevel[i - 1].length;
        graphLevelNodeIndexOffsets[i] = graphLevelNodeIndexOffsets[i - 1] + nodeCount;
      }
    }

    @Override
    public void seek(int level, int targetOrd) throws IOException {
      int targetIndex =
          level == 0
              ? targetOrd
              : Arrays.binarySearch(nodesByLevel[level], 0, nodesByLevel[level].length, targetOrd);
      assert targetIndex >= 0
          : "seek level=" + level + " target=" + targetOrd + " not found: " + targetIndex;
      // unsafe; no bounds checking
      dataIn.seek(graphLevelNodeOffsets.get(targetIndex + graphLevelNodeIndexOffsets[level]));
      arcCount = dataIn.readVInt();
      assert arcCount <= currentNeighborsBuffer.length : "too many neighbors: " + arcCount;
      if (arcCount > 0) {
        currentNeighborsBuffer[0] = dataIn.readVInt();
        for (int i = 1; i < arcCount; i++) {
          currentNeighborsBuffer[i] = currentNeighborsBuffer[i - 1] + dataIn.readVInt();
        }
      }
      arc = -1;
      arcUpTo = 0;
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public int nextNeighbor() throws IOException {
      if (arcUpTo >= arcCount) {
        return NO_MORE_DOCS;
      }
      arc = currentNeighborsBuffer[arcUpTo];
      ++arcUpTo;
      return arc;
    }

    @Override
    public int neighborCount() {
      return arcCount;
    }

    @Override
    public int numLevels() throws IOException {
      return numLevels;
    }

    @Override
    public int maxConn() {
      return maxConn;
    }

    @Override
    public int entryNode() throws IOException {
      return entryNode;
    }

    @Override
    public NodesIterator getNodesOnLevel(int level) {
      if (level == 0) {
        return new ArrayNodesIterator(size());
      } else {
        return new ArrayNodesIterator(nodesByLevel[level], nodesByLevel[level].length);
      }
    }
  }
}
