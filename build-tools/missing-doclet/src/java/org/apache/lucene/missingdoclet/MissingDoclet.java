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
package org.apache.lucene.missingdoclet;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.util.DocTrees;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Elements.Origin;
import javax.tools.Diagnostic;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;
import jdk.javadoc.doclet.StandardDoclet;

/**
 * Checks for missing javadocs, where missing also means "only whitespace" or "license header". Has
 * option --missing-level (package, class, method, parameter) so that we can improve over time. Has
 * option --missing-ignore to ignore individual elements (such as split packages). It isn't
 * recursive, just ignores exactly the elements you tell it. This should be removed when packaging
 * is fixed to no longer be split across JARs. Has option --missing-method to apply "method" level
 * to selected packages (fix one at a time). Matches package names exactly: so you'll need to list
 * subpackages separately.
 */
public class MissingDoclet extends StandardDoclet {
  // checks that modules and packages have documentation
  private static final int PACKAGE = 0;
  // + checks that classes, interfaces, enums, and annotation types have documentation
  private static final int CLASS = 1;
  // + checks that methods, constructors, fields, and enumerated constants have documentation
  private static final int METHOD = 2;
  // + checks that @param tags are present for any method/constructor parameters
  private static final int PARAMETER = 3;
  int level = PARAMETER;
  Reporter reporter;
  DocletEnvironment docEnv;
  DocTrees docTrees;
  Elements elementUtils;
  Set<String> ignored = Collections.emptySet();
  Set<String> methodPackages = Collections.emptySet();

  @Override
  public Set<Doclet.Option> getSupportedOptions() {
    Set<Doclet.Option> options = new HashSet<>(super.getSupportedOptions());
    options.add(
        new Doclet.Option() {
          @Override
          public int getArgumentCount() {
            return 1;
          }

          @Override
          public String getDescription() {
            return "level to enforce for missing javadocs: [package, class, method, parameter]";
          }

          @Override
          public Kind getKind() {
            return Option.Kind.STANDARD;
          }

          @Override
          public List<String> getNames() {
            return Collections.singletonList("--missing-level");
          }

          @Override
          public String getParameters() {
            return "level";
          }

          @Override
          public boolean process(String option, List<String> arguments) {
            switch (arguments.getFirst()) {
              case "package":
                level = PACKAGE;
                return true;
              case "class":
                level = CLASS;
                return true;
              case "method":
                level = METHOD;
                return true;
              case "parameter":
                level = PARAMETER;
                return true;
              default:
                return false;
            }
          }
        });
    options.add(
        new Doclet.Option() {
          @Override
          public int getArgumentCount() {
            return 1;
          }

          @Override
          public String getDescription() {
            return "comma separated list of element names to ignore (e.g. as a workaround for split packages)";
          }

          @Override
          public Kind getKind() {
            return Option.Kind.STANDARD;
          }

          @Override
          public List<String> getNames() {
            return Collections.singletonList("--missing-ignore");
          }

          @Override
          public String getParameters() {
            return "ignoredNames";
          }

          @Override
          public boolean process(String option, List<String> arguments) {
            ignored = new HashSet<>(Arrays.asList(arguments.get(0).split(",")));
            return true;
          }
        });
    options.add(
        new Doclet.Option() {
          @Override
          public int getArgumentCount() {
            return 1;
          }

          @Override
          public String getDescription() {
            return "comma separated list of packages to check at 'method' level";
          }

          @Override
          public Kind getKind() {
            return Option.Kind.STANDARD;
          }

          @Override
          public List<String> getNames() {
            return Collections.singletonList("--missing-method");
          }

          @Override
          public String getParameters() {
            return "packages";
          }

          @Override
          public boolean process(String option, List<String> arguments) {
            methodPackages = new HashSet<>(Arrays.asList(arguments.get(0).split(",")));
            return true;
          }
        });
    return options;
  }

  @Override
  public void init(Locale locale, Reporter reporter) {
    this.reporter = reporter;
    super.init(locale, reporter);
  }

  @Override
  public boolean run(DocletEnvironment docEnv) {
    this.docEnv = docEnv;
    this.docTrees = docEnv.getDocTrees();
    this.elementUtils = docEnv.getElementUtils();
    for (var element : docEnv.getIncludedElements()) {
      check(element);
    }

    return super.run(docEnv);
  }

  /** Returns effective check level for this element */
  private int level(Element element) {
    String pkg = elementUtils.getPackageOf(element).getQualifiedName().toString();
    if (methodPackages.contains(pkg)) {
      return METHOD;
    } else {
      return level;
    }
  }

  /**
   * Check an individual element. This checks packages and types from the doctrees. It will
   * recursively check methods/fields from encountered types when the level is "method"
   */
  private void check(Element element) {
    switch (element.getKind()) {
      case MODULE:
        // don't check the unnamed module, it won't have javadocs
        if (!((ModuleElement) element).isUnnamed()) {
          checkComment(element);
        }
        break;
      case PACKAGE:
        checkComment(element);
        break;
      // class-like elements, check them, then recursively check their children (fields and
      // methods)
      case CLASS:
      case INTERFACE:
      case ENUM:
      case RECORD:
      case ANNOTATION_TYPE:
        if (level(element) >= CLASS) {
          checkComment(element);
          if (element instanceof TypeElement te
              && element.getKind() == ElementKind.RECORD
              && level(element) >= METHOD) {
            checkRecordParameters(te, docTrees.getDocCommentTree(element));
          }
          for (var subElement : element.getEnclosedElements()) {
            // don't recurse into enclosed types, otherwise we'll double-check since they are
            // already in the included docTree
            if (subElement.getKind() == ElementKind.METHOD
                || subElement.getKind() == ElementKind.CONSTRUCTOR
                || subElement.getKind() == ElementKind.FIELD
                || subElement.getKind() == ElementKind.ENUM_CONSTANT) {
              check(subElement);
            }
          }
        }
        break;
      // method-like elements, check them if we are configured to do so
      case METHOD:
      case CONSTRUCTOR:
      case FIELD:
      case ENUM_CONSTANT:
        if (level(element) >= METHOD && !isSyntheticMethod(element)) {
          checkComment(element);
        }
        break;
      // $CASES-OMITTED$
      default:
        error(element, "I don't know how to analyze " + element.getKind() + " yet.");
    }
  }

  /**
   * Return true if the method is synthetic enum (values/valueOf) or record accessor method.
   * According to the doctree documentation, the "included" set never includes synthetic/mandated
   * elements. UweSays: It should not happen but it happens!
   */
  private boolean isSyntheticMethod(Element element) {
    // exclude all not explicitly declared methods
    if (elementUtils.getOrigin(element) != Origin.EXPLICIT) {
      return true;
    }
    // exclude record accessors
    if (element instanceof ExecutableElement ex && elementUtils.recordComponentFor(ex) != null) {
      return true;
    }
    // exclude special enum methods
    String simpleName = element.getSimpleName().toString();
    if (simpleName.equals("values") || simpleName.equals("valueOf")) {
      if (element.getEnclosingElement().getKind() == ElementKind.ENUM) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks that an element doesn't have missing javadocs. In addition to truly "missing", check
   * that comments aren't solely whitespace (generated by some IDEs), that they aren't a license
   * header masquerading as a javadoc comment.
   */
  private void checkComment(Element element) {
    // sanity check that the element is really "included", because we do some recursion into types
    if (!docEnv.isIncluded(element)) {
      return;
    }
    // check that this element isn't on our ignore list. This is only used as a workaround for
    // "split packages".
    // ignoring a package isn't recursive (on purpose), we still check all the classes, etc. inside
    // it.
    // we just need to cope with the fact package-info.java isn't there because it is split across
    // multiple jars.
    if (ignored.contains(element.toString())) {
      return;
    }
    var tree = docTrees.getDocCommentTree(element);
    if (tree == null || tree.getFirstSentence().isEmpty()) {
      // Check for methods that override other stuff and perhaps inherit their Javadocs.
      if (hasInheritedJavadocs(element)) {
        return;
      } else {
        error(element, "javadocs are missing");
      }
    } else {
      var normalized =
          tree.getFirstSentence()
              .get(0)
              .toString()
              .replace('\u00A0', ' ')
              .trim()
              .toLowerCase(Locale.ROOT);
      if (normalized.isEmpty()) {
        error(element, "blank javadoc comment");
      } else if (normalized.startsWith("licensed to the apache software foundation")
          || normalized.startsWith("copyright 2004 the apache software foundation")) {
        error(element, "comment is really a license");
      }
    }
    if (level >= PARAMETER && element instanceof ExecutableElement execEle) {
      checkMethodParameters(execEle, tree);
    }
  }

  private boolean hasInheritedJavadocs(Element element) {
    boolean hasOverrides =
        element.getAnnotationMirrors().stream()
            .anyMatch(ann -> ann.getAnnotationType().toString().equals(Override.class.getName()));

    if (hasOverrides) {
      // If an element has explicit @Overrides annotation, assume it does
      // have inherited javadocs somewhere.
      // reporter.print(Diagnostic.Kind.NOTE, element, "javadoc empty but @Override declared,
      // skipping.");
      return true;
    }

    // Check for methods up the types tree.
    if (element instanceof ExecutableElement) {
      ExecutableElement thisMethod = (ExecutableElement) element;
      Iterable<Element> superTypes =
          () -> superTypeForInheritDoc(thisMethod.getEnclosingElement()).iterator();

      for (Element sup : superTypes) {
        for (ExecutableElement supMethod : ElementFilter.methodsIn(sup.getEnclosedElements())) {
          TypeElement clazz = (TypeElement) thisMethod.getEnclosingElement();
          if (elementUtils.overrides(thisMethod, supMethod, clazz)) {
            // We could check supMethod for non-empty javadoc here. Don't know if this makes
            // sense though as all methods will be verified in the end so it'd fail on the
            // top of the hierarchy (if empty) anyway.
            // reporter.print(Diagnostic.Kind.NOTE, element, "javadoc empty but method overrides
            // another, skipping.");
            return true;
          }
        }
      }
    }

    return false;
  }

  /* Find types from which methods in type may inherit javadoc, in the proper order.*/
  private Stream<Element> superTypeForInheritDoc(Element type) {
    TypeElement clazz = (TypeElement) type;
    List<Element> interfaces =
        clazz.getInterfaces().stream()
            .filter(tm -> tm.getKind() == TypeKind.DECLARED)
            .map(tm -> ((DeclaredType) tm).asElement())
            .collect(Collectors.toList());

    Stream<Element> result = interfaces.stream();
    result = Stream.concat(result, interfaces.stream().flatMap(this::superTypeForInheritDoc));

    if (clazz.getSuperclass().getKind() == TypeKind.DECLARED) {
      Element superClass = ((DeclaredType) clazz.getSuperclass()).asElement();
      result = Stream.concat(result, Stream.of(superClass));
      result = Stream.concat(result, superTypeForInheritDoc(superClass));
    }

    return result;
  }

  /** Returns all {@code @param} parameters we see in the javadocs of the element */
  private Set<String> getDocParameters(DocCommentTree tree) {
    return Stream.ofNullable(tree)
        .flatMap(t -> t.getBlockTags().stream())
        .filter(ParamTree.class::isInstance)
        .map(tag -> ((ParamTree) tag).getName().getName().toString())
        .collect(Collectors.toSet());
  }

  /** Checks there is a corresponding "param" tag for each method parameter */
  private void checkMethodParameters(ExecutableElement element, DocCommentTree tree) {
    // record each @param that we see
    var seenParameters = getDocParameters(tree);
    // now compare the method's formal parameter list against it
    for (var param : element.getParameters()) {
      var name = param.getSimpleName().toString();
      if (!seenParameters.contains(name)) {
        error(element, "missing javadoc @param for parameter '" + name + "'");
      }
    }
  }

  /** Checks there is a corresponding "param" tag for each record component */
  private void checkRecordParameters(TypeElement element, DocCommentTree tree) {
    // record each @param that we see
    var seenParameters = getDocParameters(tree);
    // now compare the record components against it
    for (var comp : element.getRecordComponents()) {
      var name = comp.getSimpleName().toString();
      if (!seenParameters.contains(name)) {
        error(element, "missing javadoc @param for record component '" + name + "'");
      }
    }
  }

  /** logs a new error for the particular element */
  private void error(Element element, String message) {
    var fullMessage = new StringBuilder();
    switch (element.getKind()) {
      case MODULE:
      case PACKAGE:
        // for modules/packages, we don't have filename + line number, fully qualify
        fullMessage.append(element);
        break;
      case METHOD:
      case CONSTRUCTOR:
      case FIELD:
      case ENUM_CONSTANT:
        // for method-like elements, include the enclosing type to make it easier
        fullMessage.append(element.getEnclosingElement().getSimpleName());
        fullMessage.append(".");
        fullMessage.append(element.getSimpleName());
        break;
      // $CASES-OMITTED$
      default:
        // for anything else, use a simple name
        fullMessage.append(element.getSimpleName());
        break;
    }

    fullMessage.append(" (");
    fullMessage.append(element.getKind().toString().toLowerCase(Locale.ROOT));
    fullMessage.append("): ");
    fullMessage.append(message);

    if (Runtime.version().feature() == 11 && element.getKind() == ElementKind.PACKAGE) {
      // Avoid JDK 11 bug:
      // https://issues.apache.org/jira/browse/LUCENE-9747
      // https://bugs.openjdk.java.net/browse/JDK-8224082
      reporter.print(Diagnostic.Kind.ERROR, fullMessage.toString());
    } else {
      reporter.print(Diagnostic.Kind.ERROR, element, fullMessage.toString());
    }
  }
}
