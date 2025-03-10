// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine.VectorArg;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.ConfigAwareAspectBuilder;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute.LabelLateBoundDefault;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault.Resolver;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.StarlarkInfo;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.apple.XcodeConfigRule;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext;
import com.google.devtools.build.lib.rules.cpp.CcToolchainProvider;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppHelper;
import com.google.devtools.build.lib.rules.cpp.CppRuleClasses;
import com.google.devtools.build.lib.rules.cpp.CppSemantics;
import com.google.devtools.build.lib.rules.java.JavaCommon;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import com.google.devtools.build.lib.rules.java.JavaGenJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaRuleClasses;
import com.google.devtools.build.lib.rules.java.JavaRuntimeInfo;
import com.google.devtools.build.lib.rules.java.JavaSemantics;
import com.google.devtools.build.lib.rules.objc.J2ObjcSource.SourceType;
import com.google.devtools.build.lib.rules.proto.ProtoCommon;
import com.google.devtools.build.lib.rules.proto.ProtoConfiguration;
import com.google.devtools.build.lib.rules.proto.ProtoInfo;
import com.google.devtools.build.lib.rules.proto.ProtoLangToolchainProvider;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.Tuple;

/** J2ObjC transpilation aspect for Java and proto rules. */
public class J2ObjcAspect extends NativeAspectClass implements ConfiguredAspectFactory {
  public static final String NAME = "J2ObjcAspect";

  private static LabelLateBoundDefault<ProtoConfiguration> getProtoToolchainLabel(
      String defaultValue) {
    return LabelLateBoundDefault.fromTargetConfiguration(
        ProtoConfiguration.class,
        Label.parseAbsoluteUnchecked(defaultValue),
        (Resolver<ProtoConfiguration, Label> & Serializable)
            (rule, attributes, protoConfig) -> protoConfig.protoToolchainForJ2objc());
  }

  private static final ImmutableList<String> JAVA_DEPENDENT_ATTRIBUTES =
      ImmutableList.of("$jre_lib", "deps", "exports", "runtime_deps");

  private static final ImmutableList<String> PROTO_DEPENDENT_ATTRIBUTES = ImmutableList.of("deps");

  private static final String J2OBJC_PROTO_TOOLCHAIN_ATTR = ":j2objc_proto_toolchain";

  @SerializationConstant @AutoCodec.VisibleForSerialization
  static final LabelLateBoundDefault<?> DEAD_CODE_REPORT =
      LabelLateBoundDefault.fromTargetConfiguration(
          J2ObjcConfiguration.class,
          null,
          (rule, attributes, j2objcConfig) -> j2objcConfig.deadCodeReport());

  private final RepositoryName toolsRepository;
  private final Label ccToolchainType;
  private final LabelLateBoundDefault<CppConfiguration> ccToolchain;
  private final ToolchainTypeRequirement javaToolchainTypeRequirement;

  public J2ObjcAspect(RuleDefinitionEnvironment env, CppSemantics cppSemantics) {
    this.toolsRepository = checkNotNull(env.getToolsRepository());
    this.ccToolchainType = CppRuleClasses.ccToolchainTypeAttribute(env);
    this.ccToolchain = CppRuleClasses.ccToolchainAttribute(env);
    this.javaToolchainTypeRequirement = JavaRuleClasses.javaToolchainTypeRequirement(env);
  }

  /** Returns whether this aspect allows proto services to be generated from this proto rule */
  protected boolean shouldAllowProtoServices(RuleContext ruleContext) {
    return true;
  }

  @Override
  public AspectDefinition getDefinition(AspectParameters aspectParameters) {
    return ConfigAwareAspectBuilder.of(new AspectDefinition.Builder(this))
        .originalBuilder()
        .propagateAlongAttribute("deps")
        .propagateAlongAttribute("exports")
        .propagateAlongAttribute("runtime_deps")
        .requireStarlarkProviders(StarlarkProviderIdentifier.forKey(JavaInfo.PROVIDER.getKey()))
        .requireStarlarkProviders(ProtoInfo.PROVIDER.id())
        .advertiseProvider(ImmutableList.of(ObjcProvider.STARLARK_CONSTRUCTOR.id()))
        .requiresConfigurationFragments(
            AppleConfiguration.class,
            CppConfiguration.class,
            J2ObjcConfiguration.class,
            ObjcConfiguration.class,
            ProtoConfiguration.class)
        .addToolchainTypes(
            CppRuleClasses.ccToolchainTypeRequirement(ccToolchainType),
            javaToolchainTypeRequirement)
        .add(
            attr("$grep_includes", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .value(Label.parseAbsoluteUnchecked(toolsRepository + "//tools/cpp:grep-includes")))
        .add(
            attr("$j2objc", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .exec()
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//tools/j2objc:j2objc_deploy.jar")))
        .add(
            attr("$j2objc_wrapper", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .exec()
                .legacyAllowAnyFileType()
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//tools/j2objc:j2objc_wrapper_binary")))
        .add(
            attr("$j2objc_header_map", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .exec()
                .legacyAllowAnyFileType()
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//tools/j2objc:j2objc_header_map_binary")))
        .add(
            attr("$jre_emul_jar", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//third_party/java/j2objc:jre_emul.jar")))
        .add(
            attr("$jre_emul_module", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//third_party/java/j2objc:jre_emul_module")))
        .add(
            attr(":dead_code_report", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .value(DEAD_CODE_REPORT))
        .add(
            attr("$jre_lib", LABEL)
                .value(
                    Label.parseAbsoluteUnchecked(
                        toolsRepository + "//third_party/java/j2objc:jre_core_lib")))
        .add(
            attr("$xcrunwrapper", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .exec()
                .value(Label.parseAbsoluteUnchecked(toolsRepository + "//tools/objc:xcrunwrapper")))
        .add(
            attr(XcodeConfigRule.XCODE_CONFIG_ATTR_NAME, LABEL)
                .allowedRuleClasses("xcode_config")
                .checkConstraints()
                .direct_compile_time_input()
                .value(AppleToolchain.getXcodeConfigLabel(toolsRepository)))
        .add(
            attr("$zipper", LABEL)
                .cfg(ExecutionTransitionFactory.create())
                .exec()
                .value(Label.parseAbsoluteUnchecked(toolsRepository + "//tools/zip:zipper")))
        .add(
            attr(J2OBJC_PROTO_TOOLCHAIN_ATTR, LABEL)
                .legacyAllowAnyFileType()
                .value(
                    getProtoToolchainLabel(
                        toolsRepository + "//tools/j2objc:j2objc_proto_toolchain")))
        .add(attr(":j2objc_cc_toolchain", LABEL).value(ccToolchain))
        .add(
            attr(JavaRuleClasses.JAVA_TOOLCHAIN_TYPE_ATTRIBUTE_NAME, LABEL)
                .value(javaToolchainTypeRequirement.toolchainType()))
        .build();
  }

  @Override
  public ConfiguredAspect create(
      ConfiguredTargetAndData ctadBase,
      RuleContext ruleContext,
      AspectParameters parameters,
      RepositoryName toolsRepository)
      throws InterruptedException, ActionConflictException {
    ConfiguredTarget base = ctadBase.getConfiguredTarget();
    if (isProtoRule(base)) {
      return proto(base, ruleContext);
    } else {
      return java(base, ruleContext);
    }
  }

  private ConfiguredAspect buildAspect(
      ConfiguredTarget base,
      RuleContext ruleContext,
      J2ObjcSource j2ObjcSource,
      J2ObjcMappingFileProvider directJ2ObjcMappingFileProvider,
      List<String> depAttributes,
      List<TransitiveInfoCollection> otherDeps)
      throws InterruptedException, ActionConflictException {
    ConfiguredAspect.Builder builder = new ConfiguredAspect.Builder(ruleContext);
    ObjcCommon common;
    CcCompilationContext ccCompilationContext = null;
    CcLinkingContext ccLinkingContext = null;

    IntermediateArtifacts intermediateArtifacts =
        ObjcRuleClasses.j2objcIntermediateArtifacts(ruleContext);
    if (!j2ObjcSource.getObjcSrcs().isEmpty()) {
      common =
          common(
              ObjcCommon.Purpose.COMPILE_AND_LINK,
              ruleContext,
              intermediateArtifacts,
              j2ObjcSource.getObjcSrcs(),
              j2ObjcSource.getObjcHdrs(),
              j2ObjcSource.getHeaderSearchPaths(),
              depAttributes,
              otherDeps);

      try {
        CcToolchainProvider ccToolchain =
            CppHelper.getToolchain(ruleContext, ":j2objc_cc_toolchain");
        ImmutableList<String> extraCompileArgs =
            j2objcCompileWithARC(ruleContext)
                ? ImmutableList.of("-fno-strict-overflow", "-fobjc-arc-exceptions")
                : ImmutableList.of("-fno-strict-overflow", "-fobjc-weak");

        Object starlarkFunc =
            ruleContext.getStarlarkDefinedBuiltin(
                "register_compile_and_archive_actions_for_j2objc");
        ruleContext.initStarlarkRuleContext();
        Tuple compilationResult =
            (Tuple)
                ruleContext.callStarlarkOrThrowRuleError(
                    starlarkFunc,
                    ImmutableList.of(
                        ruleContext.getStarlarkRuleContext(),
                        ccToolchain,
                        intermediateArtifacts,
                        common.getCompilationArtifacts().get(),
                        common.getObjcCompilationContext(),
                        StarlarkList.immutableCopyOf(common.getCcLinkingContexts()),
                        StarlarkList.immutableCopyOf(extraCompileArgs)),
                    new HashMap<>());

        ccCompilationContext = (CcCompilationContext) compilationResult.get(0);
        ccLinkingContext = (CcLinkingContext) compilationResult.get(1);
      } catch (RuleErrorException e) {
        ruleContext.ruleError(e.getMessage());
      }
    } else {
      common =
          common(
              ObjcCommon.Purpose.LINK_ONLY,
              ruleContext,
              intermediateArtifacts,
              ImmutableList.<Artifact>of(),
              ImmutableList.<Artifact>of(),
              ImmutableList.<PathFragment>of(),
              depAttributes,
              otherDeps);
      ccCompilationContext = common.createCcCompilationContext();
      ccLinkingContext = common.createCcLinkingContext();
    }

    return builder
        .addNativeDeclaredProvider(
            exportedJ2ObjcMappingFileProvider(base, ruleContext, directJ2ObjcMappingFileProvider))
        .addNativeDeclaredProvider(common.getObjcProvider())
        .addNativeDeclaredProvider(
            CcInfo.builder()
                .setCcCompilationContext(ccCompilationContext)
                .setCcLinkingContext(ccLinkingContext)
                .build())
        .build();
  }

  private ConfiguredAspect java(ConfiguredTarget base, RuleContext ruleContext)
      throws InterruptedException, ActionConflictException {
    JavaCompilationArgsProvider compilationArgsProvider =
        JavaInfo.getProvider(JavaCompilationArgsProvider.class, base);
    JavaGenJarsProvider genJarProvider = JavaInfo.getProvider(JavaGenJarsProvider.class, base);
    ImmutableSet.Builder<Artifact> javaSourceFilesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<Artifact> javaSourceJarsBuilder = ImmutableSet.builder();

    for (Artifact srcArtifact : ruleContext.getPrerequisiteArtifacts("srcs").list()) {
      String srcFilename = srcArtifact.getExecPathString();
      if (JavaSemantics.SOURCE_JAR.apply(srcFilename)) {
        javaSourceJarsBuilder.add(srcArtifact);
      } else if (JavaSemantics.JAVA_SOURCE.apply(srcFilename)) {
        javaSourceFilesBuilder.add(srcArtifact);
      }
    }
    Artifact srcJar =
        ruleContext.attributes().has("srcjar")
            ? ruleContext.getPrerequisiteArtifact("srcjar")
            : null;
    if (srcJar != null) {
      javaSourceJarsBuilder.add(srcJar);
    }

    if (genJarProvider != null && genJarProvider.getGenSourceJar() != null) {
      javaSourceJarsBuilder.add(genJarProvider.getGenSourceJar());
    }

    ImmutableList<Artifact> javaSourceFiles = javaSourceFilesBuilder.build().asList();
    ImmutableList<Artifact> javaSourceJars = javaSourceJarsBuilder.build().asList();
    J2ObjcSource j2ObjcSource = javaJ2ObjcSource(ruleContext, javaSourceFiles, javaSourceJars);
    J2ObjcMappingFileProvider depJ2ObjcMappingFileProvider =
        depJ2ObjcMappingFileProvider(ruleContext);

    J2ObjcMappingFileProvider directJ2ObjcMappingFileProvider;
    if (j2ObjcSource.getObjcSrcs().isEmpty()) {
      directJ2ObjcMappingFileProvider = new J2ObjcMappingFileProvider.Builder().build();
    } else {
      directJ2ObjcMappingFileProvider =
          createJ2ObjcTranspilationAction(
              ruleContext,
              javaSourceFiles,
              javaSourceJars,
              depJ2ObjcMappingFileProvider,
              compilationArgsProvider,
              j2ObjcSource);
    }
    return buildAspect(
        base,
        ruleContext,
        j2ObjcSource,
        directJ2ObjcMappingFileProvider,
        JAVA_DEPENDENT_ATTRIBUTES,
        ImmutableList.of());
  }

  @Nullable
  private ConfiguredAspect proto(ConfiguredTarget base, RuleContext ruleContext)
      throws InterruptedException, ActionConflictException {
    ProtoLangToolchainProvider protoToolchain =
        ProtoLangToolchainProvider.get(ruleContext, J2OBJC_PROTO_TOOLCHAIN_ATTR);
    StarlarkInfo starlarkProtoToolchain =
        ProtoLangToolchainProvider.getStarlarkProvider(ruleContext, J2OBJC_PROTO_TOOLCHAIN_ATTR);
    try {
      // Avoid pulling in any generated files from forbidden protos.
      ImmutableList<Artifact> filteredProtoSources =
          ImmutableList.copyOf(
              ProtoCommon.filterSources(ruleContext, base, starlarkProtoToolchain));

      J2ObjcSource j2ObjcSource = protoJ2ObjcSource(ruleContext, base, filteredProtoSources);

      J2ObjcMappingFileProvider directJ2ObjcMappingFileProvider;
      if (j2ObjcSource.getObjcSrcs().isEmpty()) {
        directJ2ObjcMappingFileProvider = new J2ObjcMappingFileProvider.Builder().build();
      } else {

        directJ2ObjcMappingFileProvider =
            createJ2ObjcProtoCompileActions(
                base, starlarkProtoToolchain, ruleContext, filteredProtoSources, j2ObjcSource);
      }

      return buildAspect(
          base,
          ruleContext,
          j2ObjcSource,
          directJ2ObjcMappingFileProvider,
          PROTO_DEPENDENT_ATTRIBUTES,
          ImmutableList.of(protoToolchain.runtime()));
    } catch (RuleErrorException e) {
      ruleContext.ruleError(e.getMessage());
      return null;
    }
  }

  private static J2ObjcMappingFileProvider exportedJ2ObjcMappingFileProvider(
      ConfiguredTarget base,
      RuleContext ruleContext,
      J2ObjcMappingFileProvider directJ2ObjcMappingFileProvider) {
    J2ObjcMappingFileProvider depJ2ObjcMappingFileProvider =
        depJ2ObjcMappingFileProvider(ruleContext);

    NestedSetBuilder<Artifact> exportedHeaderMappingFiles =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(directJ2ObjcMappingFileProvider.getHeaderMappingFiles());

    NestedSetBuilder<Artifact> exportedClassMappingFiles =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(directJ2ObjcMappingFileProvider.getClassMappingFiles())
            .addTransitive(depJ2ObjcMappingFileProvider.getClassMappingFiles());

    NestedSetBuilder<Artifact> exportedDependencyMappingFiles =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(directJ2ObjcMappingFileProvider.getDependencyMappingFiles())
            .addTransitive(depJ2ObjcMappingFileProvider.getDependencyMappingFiles());

    NestedSetBuilder<Artifact> archiveSourceMappingFiles =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(directJ2ObjcMappingFileProvider.getArchiveSourceMappingFiles())
            .addTransitive(depJ2ObjcMappingFileProvider.getArchiveSourceMappingFiles());

    // J2ObjC merges all transitive input header mapping files into one header mapping file,
    // so we only need to re-export other dependent output header mapping files in proto rules and
    // rules where J2ObjC is not run (e.g., no sources).
    // We also add the transitive header mapping files if experimental J2ObjC header mapping is
    // turned on. The experimental support does not merge transitive input header mapping files.
    boolean experimentalJ2ObjcHeaderMap =
        ruleContext.getFragment(J2ObjcConfiguration.class).experimentalJ2ObjcHeaderMap();
    if (isProtoRule(base) || exportedHeaderMappingFiles.isEmpty() || experimentalJ2ObjcHeaderMap) {
      exportedHeaderMappingFiles.addTransitive(
          depJ2ObjcMappingFileProvider.getHeaderMappingFiles());
    }

    return new J2ObjcMappingFileProvider(
        exportedHeaderMappingFiles.build(),
        exportedClassMappingFiles.build(),
        exportedDependencyMappingFiles.build(),
        archiveSourceMappingFiles.build());
  }

  private static J2ObjcMappingFileProvider depJ2ObjcMappingFileProvider(RuleContext ruleContext) {
    NestedSetBuilder<Artifact> depsHeaderMappingsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> depsClassMappingsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> depsDependencyMappingsBuilder = NestedSetBuilder.stableOrder();
    NestedSetBuilder<Artifact> depsArchiveSourceMappingsBuilder = NestedSetBuilder.stableOrder();

    for (J2ObjcMappingFileProvider mapping : getJ2ObjCMappings(ruleContext)) {
      depsHeaderMappingsBuilder.addTransitive(mapping.getHeaderMappingFiles());
      depsClassMappingsBuilder.addTransitive(mapping.getClassMappingFiles());
      depsDependencyMappingsBuilder.addTransitive(mapping.getDependencyMappingFiles());
      depsArchiveSourceMappingsBuilder.addTransitive(mapping.getArchiveSourceMappingFiles());
    }

    return new J2ObjcMappingFileProvider(
        depsHeaderMappingsBuilder.build(),
        depsClassMappingsBuilder.build(),
        depsDependencyMappingsBuilder.build(),
        depsArchiveSourceMappingsBuilder.build());
  }

  private static ImmutableList<String> sourceJarFlags(RuleContext ruleContext) {
    return ImmutableList.of(
        "--output_gen_source_dir",
        j2ObjcSourceJarTranslatedSourceTreeArtifact(ruleContext).getExecPathString(),
        "--output_gen_header_dir",
        j2objcSourceJarTranslatedHeaderTreeArtifact(ruleContext).getExecPathString());
  }

  private static J2ObjcMappingFileProvider createJ2ObjcTranspilationAction(
      RuleContext ruleContext,
      ImmutableList<Artifact> sources,
      ImmutableList<Artifact> sourceJars,
      J2ObjcMappingFileProvider depJ2ObjcMappingFileProvider,
      JavaCompilationArgsProvider compArgsProvider,
      J2ObjcSource j2ObjcSource) {
    CustomCommandLine.Builder argBuilder = CustomCommandLine.builder();
    PathFragment javaExecutable = JavaCommon.getHostJavaExecutable(ruleContext);
    argBuilder.add("--java", javaExecutable.getPathString());

    Artifact j2ObjcDeployJar = ruleContext.getPrerequisiteArtifact("$j2objc");
    argBuilder.addExecPath("--j2objc", j2ObjcDeployJar);

    argBuilder.add("--main_class").add("com.google.devtools.j2objc.J2ObjC");
    argBuilder.add("--objc_file_path").addPath(j2ObjcSource.getObjcFilePath());

    Artifact outputDependencyMappingFile = j2ObjcOutputDependencyMappingFile(ruleContext);
    argBuilder.addExecPath("--output_dependency_mapping_file", outputDependencyMappingFile);

    if (!sourceJars.isEmpty()) {
      argBuilder.addExecPaths(
          "--src_jars", VectorArg.join(",").each(ImmutableList.copyOf(sourceJars)));
      argBuilder.addAll(sourceJarFlags(ruleContext));
    }

    List<String> translationFlags =
        ruleContext.getFragment(J2ObjcConfiguration.class).getTranslationFlags();
    argBuilder.addAll(ImmutableList.copyOf(translationFlags));

    NestedSet<Artifact> depsHeaderMappingFiles =
        depJ2ObjcMappingFileProvider.getHeaderMappingFiles();
    if (!depsHeaderMappingFiles.isEmpty()) {
      argBuilder.addExecPaths("--header-mapping", VectorArg.join(",").each(depsHeaderMappingFiles));
    }

    boolean experimentalJ2ObjcHeaderMap =
        ruleContext.getFragment(J2ObjcConfiguration.class).experimentalJ2ObjcHeaderMap();
    Artifact outputHeaderMappingFile = j2ObjcOutputHeaderMappingFile(ruleContext);
    if (!experimentalJ2ObjcHeaderMap) {
      argBuilder.addExecPath("--output-header-mapping", outputHeaderMappingFile);
    }

    NestedSet<Artifact> depsClassMappingFiles = depJ2ObjcMappingFileProvider.getClassMappingFiles();
    if (!depsClassMappingFiles.isEmpty()) {
      argBuilder.addExecPaths("--mapping", VectorArg.join(",").each(depsClassMappingFiles));
    }

    Artifact archiveSourceMappingFile = j2ObjcOutputArchiveSourceMappingFile(ruleContext);
    argBuilder.addExecPath("--output_archive_source_mapping_file", archiveSourceMappingFile);

    Artifact compiledLibrary = ObjcRuleClasses.j2objcIntermediateArtifacts(ruleContext).archive();
    argBuilder.addExecPath("--compiled_archive_file_path", compiledLibrary);

    Artifact bootclasspathJar = ruleContext.getPrerequisiteArtifact("$jre_emul_jar");
    argBuilder.addFormatted("-Xbootclasspath:%s", bootclasspathJar);

    // A valid Java system module contains 3 files. The top directory contains a file "release".
    ImmutableList<Artifact> moduleFiles =
        ruleContext.getPrerequisiteArtifacts("$jre_emul_module").list();
    for (Artifact a : moduleFiles) {
      if (a.getFilename().equals("release")) {
        argBuilder.add("--system", a.getDirname());
        break;
      }
    }

    Artifact deadCodeReport = ruleContext.getPrerequisiteArtifact(":dead_code_report");
    if (deadCodeReport != null) {
      argBuilder.addExecPath("--dead-code-report", deadCodeReport);
    }

    argBuilder.add("-d").addPath(j2ObjcSource.getObjcFilePath());

    NestedSet<Artifact> compileTimeJars = compArgsProvider.getTransitiveCompileTimeJars();
    if (!compileTimeJars.isEmpty()) {
      argBuilder.addExecPaths("-classpath", VectorArg.join(":").each(compileTimeJars));
    }

    argBuilder.addExecPaths(sources);

    SpawnAction.Builder transpilationAction =
        new SpawnAction.Builder()
            .setMnemonic("TranspilingJ2objc")
            .setExecutable(ruleContext.getExecutablePrerequisite("$j2objc_wrapper"))
            .addInput(j2ObjcDeployJar)
            .addInput(bootclasspathJar)
            .addInputs(moduleFiles)
            .addInputs(sources)
            .addInputs(sourceJars)
            .addTransitiveInputs(compileTimeJars)
            .addTransitiveInputs(JavaRuntimeInfo.forHost(ruleContext).javaBaseInputs())
            .addTransitiveInputs(depsHeaderMappingFiles)
            .addTransitiveInputs(depsClassMappingFiles)
            .addCommandLine(
                argBuilder.build(),
                ParamFileInfo.builder(ParameterFile.ParameterFileType.UNQUOTED)
                    .setCharset(ISO_8859_1)
                    .setUseAlways(true)
                    .build())
            .addOutputs(j2ObjcSource.getObjcSrcs())
            .addOutputs(j2ObjcSource.getObjcHdrs())
            .addOutput(outputDependencyMappingFile)
            .addOutput(archiveSourceMappingFile);

    if (deadCodeReport != null) {
      transpilationAction.addInput(deadCodeReport);
    }

    if (!experimentalJ2ObjcHeaderMap) {
      transpilationAction.addOutput(outputHeaderMappingFile);
    }
    ruleContext.registerAction(transpilationAction.build(ruleContext));

    if (experimentalJ2ObjcHeaderMap) {
      CustomCommandLine.Builder headerMapCommandLine = CustomCommandLine.builder();
      if (!sources.isEmpty()) {
        headerMapCommandLine.addExecPaths("--source_files", VectorArg.join(",").each(sources));
      }
      if (!sourceJars.isEmpty()) {
        headerMapCommandLine.addExecPaths("--source_jars", VectorArg.join(",").each(sourceJars));
      }
      headerMapCommandLine.addExecPath("--output_mapping_file", outputHeaderMappingFile);
      ruleContext.registerAction(
          new SpawnAction.Builder()
              .setMnemonic("GenerateJ2objcHeaderMap")
              .setExecutable(ruleContext.getExecutablePrerequisite("$j2objc_header_map"))
              .addInputs(sources)
              .addInputs(sourceJars)
              .addCommandLine(
                  headerMapCommandLine.build(),
                  ParamFileInfo.builder(ParameterFileType.SHELL_QUOTED).build())
              .addOutput(outputHeaderMappingFile)
              .build(ruleContext));
    }

    return new J2ObjcMappingFileProvider(
        NestedSetBuilder.<Artifact>stableOrder().add(outputHeaderMappingFile).build(),
        NestedSetBuilder.<Artifact>stableOrder().build(),
        NestedSetBuilder.<Artifact>stableOrder().add(outputDependencyMappingFile).build(),
        NestedSetBuilder.<Artifact>stableOrder().add(archiveSourceMappingFile).build());
  }

  private J2ObjcMappingFileProvider createJ2ObjcProtoCompileActions(
      ConfiguredTarget base,
      StarlarkInfo protoToolchain,
      RuleContext ruleContext,
      ImmutableList<Artifact> filteredProtoSources,
      J2ObjcSource j2ObjcSource)
      throws RuleErrorException, InterruptedException {
    ImmutableList<Artifact> outputHeaderMappingFiles =
        filteredProtoSources.isEmpty()
            ? ImmutableList.of()
            : ProtoCommon.declareGeneratedFiles(ruleContext, base, ".j2objc.mapping");
    ImmutableList<Artifact> outputClassMappingFiles =
        filteredProtoSources.isEmpty()
            ? ImmutableList.of()
            : ProtoCommon.declareGeneratedFiles(ruleContext, base, ".clsmap.properties");
    ImmutableList<Artifact> outputs =
        ImmutableList.<Artifact>builder()
            .addAll(j2ObjcSource.getObjcSrcs())
            .addAll(j2ObjcSource.getObjcHdrs())
            .addAll(outputHeaderMappingFiles)
            .addAll(outputClassMappingFiles)
            .build();

    String bindirPath = getProtoOutputRoot(ruleContext).getPathString();

    ProtoCommon.compile(
        ruleContext,
        base,
        checkNotNull(protoToolchain),
        outputs,
        bindirPath,
        "Generating j2objc proto_library %{label}");

    return new J2ObjcMappingFileProvider(
        NestedSetBuilder.<Artifact>stableOrder().addAll(outputHeaderMappingFiles).build(),
        NestedSetBuilder.<Artifact>stableOrder().addAll(outputClassMappingFiles).build(),
        NestedSetBuilder.<Artifact>stableOrder().build(),
        NestedSetBuilder.<Artifact>stableOrder().build());
  }

  private static List<? extends J2ObjcMappingFileProvider> getJ2ObjCMappings(RuleContext context) {
    ImmutableList.Builder<J2ObjcMappingFileProvider> mappingFileProviderBuilder =
        new ImmutableList.Builder<>();
    addJ2ObjCMappingsForAttribute(mappingFileProviderBuilder, context, "deps");
    addJ2ObjCMappingsForAttribute(mappingFileProviderBuilder, context, "runtime_deps");
    addJ2ObjCMappingsForAttribute(mappingFileProviderBuilder, context, "exports");
    return mappingFileProviderBuilder.build();
  }

  private static void addJ2ObjCMappingsForAttribute(
      ImmutableList.Builder<J2ObjcMappingFileProvider> builder,
      RuleContext context,
      String attributeName) {
    if (context.attributes().has(attributeName, BuildType.LABEL_LIST)) {
      for (TransitiveInfoCollection dependencyInfoDatum : context.getPrerequisites(attributeName)) {
        J2ObjcMappingFileProvider provider =
            dependencyInfoDatum.get(J2ObjcMappingFileProvider.PROVIDER);
        if (provider != null) {
          builder.add(provider);
        }
      }
    }
  }

  private static Artifact j2ObjcOutputHeaderMappingFile(RuleContext ruleContext) {
    return ObjcRuleClasses.artifactByAppendingToBaseName(ruleContext, ".mapping.j2objc");
  }

  private static Artifact j2ObjcOutputDependencyMappingFile(RuleContext ruleContext) {
    return ObjcRuleClasses.artifactByAppendingToBaseName(ruleContext, ".dependency_mapping.j2objc");
  }

  private static Artifact j2ObjcOutputArchiveSourceMappingFile(RuleContext ruleContext) {
    return ObjcRuleClasses.artifactByAppendingToBaseName(
        ruleContext, ".archive_source_mapping.j2objc");
  }

  private static Artifact j2ObjcSourceJarTranslatedSourceTreeArtifact(RuleContext ruleContext) {
    PathFragment rootRelativePath =
        ruleContext.getUniqueDirectory("_j2objc/src_jar_files").getRelative("source_files");
    return ruleContext.getTreeArtifact(rootRelativePath, ruleContext.getBinOrGenfilesDirectory());
  }

  /**
   * Returns a unique path fragment for j2objc headers. The slightly shorter path is useful for very
   * large app builds, which otherwise may have command lines that are too long to be executable.
   */
  private static String j2objcHeaderBase(RuleContext ruleContext) {
    boolean shorterPath =
        ruleContext.getFragment(J2ObjcConfiguration.class).experimentalShorterHeaderPath();
    return shorterPath ? "_ios" : "_j2objc";
  }

  private static Artifact j2objcSourceJarTranslatedHeaderTreeArtifact(RuleContext ruleContext) {
    String uniqueDirectoryPath = j2objcHeaderBase(ruleContext) + "/src_jar_files";
    PathFragment rootRelativePath =
        ruleContext.getUniqueDirectory(uniqueDirectoryPath).getRelative("header_files");
    return ruleContext.getTreeArtifact(rootRelativePath, ruleContext.getBinOrGenfilesDirectory());
  }

  private static boolean j2objcCompileWithARC(RuleContext ruleContext) {
    return ruleContext.getFragment(J2ObjcConfiguration.class).compileWithARC();
  }

  private static J2ObjcSource javaJ2ObjcSource(
      RuleContext ruleContext,
      ImmutableList<Artifact> javaInputSourceFiles,
      ImmutableList<Artifact> javaSourceJarFiles) {
    PathFragment objcFileRootRelativePath =
        ruleContext.getUniqueDirectory(j2objcHeaderBase(ruleContext));
    PathFragment objcFileRootExecPath =
        ruleContext.getBinFragment().getRelative(objcFileRootRelativePath);

    // Note that these are mutable lists so that we can add the translated file info below.
    List<Artifact> objcSrcs =
        getOutputObjcFiles(ruleContext, javaInputSourceFiles, objcFileRootRelativePath, ".m");
    List<Artifact> objcHdrs =
        getOutputObjcFiles(ruleContext, javaInputSourceFiles, objcFileRootRelativePath, ".h");
    List<PathFragment> headerSearchPaths =
        j2objcSourceHeaderSearchPaths(ruleContext, objcFileRootExecPath, javaInputSourceFiles);
    if (!javaSourceJarFiles.isEmpty()) {
      // Add the translated source + header files.
      objcSrcs.add(j2ObjcSourceJarTranslatedSourceTreeArtifact(ruleContext));
      Artifact translatedHeader = j2objcSourceJarTranslatedHeaderTreeArtifact(ruleContext);
      objcHdrs.add(translatedHeader);
      headerSearchPaths.add(translatedHeader.getExecPath());
    }

    return new J2ObjcSource(
        ruleContext.getRule().getLabel(),
        objcSrcs,
        objcHdrs,
        objcFileRootExecPath,
        SourceType.JAVA,
        headerSearchPaths,
        j2objcCompileWithARC(ruleContext));
  }

  private static J2ObjcSource protoJ2ObjcSource(
      RuleContext ruleContext, ConfiguredTarget protoTarget, ImmutableList<Artifact> protoSources)
      throws RuleErrorException, InterruptedException {
    PathFragment objcFileRootExecPath = getProtoOutputRoot(ruleContext);

    List<PathFragment> headerSearchPaths =
        j2objcSourceHeaderSearchPaths(ruleContext, objcFileRootExecPath, protoSources);

    return new J2ObjcSource(
        ruleContext.getTarget().getLabel(),
        protoSources.isEmpty()
            ? ImmutableList.of()
            : ProtoCommon.declareGeneratedFiles(ruleContext, protoTarget, ".j2objc.pb.m"),
        protoSources.isEmpty()
            ? ImmutableList.of()
            : ProtoCommon.declareGeneratedFiles(ruleContext, protoTarget, ".j2objc.pb.h"),
        objcFileRootExecPath,
        SourceType.PROTO,
        headerSearchPaths,
        /*compileWithARC=*/ false); // generated protos do not support ARC.
  }

  private static PathFragment getProtoOutputRoot(RuleContext ruleContext) {
    if (ruleContext.getConfiguration().isSiblingRepositoryLayout()) {
      return ruleContext.getBinFragment();
    }
    return ruleContext
        .getBinFragment()
        .getRelative(ruleContext.getLabel().getRepository().getExecPath(false));
  }

  private static boolean isProtoRule(ConfiguredTarget base) {
    return base.get(ProtoInfo.PROVIDER) != null;
  }

  /** Returns a mutable List of objc output files. */
  private static List<Artifact> getOutputObjcFiles(
      RuleContext ruleContext,
      Collection<Artifact> javaSrcs,
      PathFragment objcFileRootRelativePath,
      String suffix) {
    List<Artifact> objcSources = new ArrayList<>();
    for (Artifact javaSrc : javaSrcs) {
      objcSources.add(
          ruleContext.getRelatedArtifact(
              objcFileRootRelativePath.getRelative(javaSrc.getExecPath()), suffix));
    }
    return objcSources;
  }

  /**
   * Returns a mutable list of header search paths necessary to compile the J2ObjC-generated code
   * from a single target.
   *
   * @param ruleContext the rule context
   * @param objcFileRootExecPath the exec path under which all J2ObjC-generated file resides
   * @param sourcesToTranslate the source files to be translated by J2ObjC in a single target
   */
  private static List<PathFragment> j2objcSourceHeaderSearchPaths(
      RuleContext ruleContext,
      PathFragment objcFileRootExecPath,
      Collection<Artifact> sourcesToTranslate) {
    PathFragment genRoot = ruleContext.getGenfilesFragment();
    List<PathFragment> headerSearchPaths = new ArrayList<>();
    headerSearchPaths.add(objcFileRootExecPath);
    // We add another header search path with gen root if we have generated sources to translate.
    for (Artifact sourceToTranslate : sourcesToTranslate) {
      if (!sourceToTranslate.isSourceArtifact()) {
        headerSearchPaths.add(objcFileRootExecPath.getRelative(genRoot));
        return headerSearchPaths;
      }
    }

    return headerSearchPaths;
  }

  /** Sets up and returns an {@link ObjcCommon} object containing the J2ObjC-translated code. */
  private static ObjcCommon common(
      ObjcCommon.Purpose purpose,
      RuleContext ruleContext,
      IntermediateArtifacts intermediateArtifacts,
      List<Artifact> transpiledSources,
      List<Artifact> transpiledHeaders,
      List<PathFragment> headerSearchPaths,
      List<String> dependentAttributes,
      List<TransitiveInfoCollection> otherDeps)
      throws InterruptedException {
    ObjcCommon.Builder builder = new ObjcCommon.Builder(purpose, ruleContext);

    if (!transpiledSources.isEmpty() || !transpiledHeaders.isEmpty()) {
      CompilationArtifacts.Builder compilationArtifactsBuilder =
          new CompilationArtifacts.Builder()
              .setIntermediateArtifacts(intermediateArtifacts)
              .addAdditionalHdrs(transpiledHeaders);
      if (j2objcCompileWithARC(ruleContext)) {
        compilationArtifactsBuilder.addSrcs(transpiledSources);
      } else {
        compilationArtifactsBuilder.addNonArcSrcs(transpiledSources);
      }
      builder.setCompilationArtifacts(compilationArtifactsBuilder.build());
      builder.setHasModuleMap();
    }

    ImmutableList.Builder<CcInfo> ccInfos = new ImmutableList.Builder<>();
    for (String attrName : dependentAttributes) {
      if (ruleContext.attributes().has(attrName, BuildType.LABEL_LIST)
          || ruleContext.attributes().has(attrName, BuildType.LABEL)) {
        for (TransitiveInfoCollection dep : ruleContext.getPrerequisites(attrName)) {
          CcInfo ccInfo = dep.get(CcInfo.PROVIDER);
          if (ccInfo != null) {
            ccInfos.add(ccInfo);
          }
        }
        builder.addObjcProviders(
            ruleContext.getPrerequisites(attrName, ObjcProvider.STARLARK_CONSTRUCTOR));
      }
    }
    builder.addCcInfos(ccInfos.build());

    // We can't just use addDeps since that now takes ConfiguredTargetAndData and we only have
    // TransitiveInfoCollections
    builder.addObjcProviders(
        otherDeps.stream()
            .map(d -> d.get(ObjcProvider.STARLARK_CONSTRUCTOR))
            .collect(toImmutableList()));
    builder.addCcInfos(
        otherDeps.stream().map(d -> d.get(CcInfo.PROVIDER)).collect(toImmutableList()));

    return builder
        .addIncludes(headerSearchPaths)
        .setIntermediateArtifacts(intermediateArtifacts)
        .build();
  }
}
