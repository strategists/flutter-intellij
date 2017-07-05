/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.test;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.RunConfigurationProducer;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.jetbrains.lang.dart.psi.*;
import io.flutter.dart.DartPlugin;
import io.flutter.dart.DartSyntax;
import io.flutter.pub.PubRoot;
import io.flutter.run.FlutterRunConfigurationProducer;
import io.flutter.utils.FlutterModuleUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Determines when we can run a test using "flutter test".
 */
public class TestConfigProducer extends RunConfigurationProducer<TestConfig> {

  protected TestConfigProducer() {
    super(TestConfigType.getInstance());
  }

  /**
   * If the current file looks like a Flutter test, initializes the run config to run it.
   * <p>
   * Returns true if successfully set up.
   */
  @Override
  protected boolean setupConfigurationFromContext(TestConfig config, ConfigurationContext context, Ref<PsiElement> sourceElement) {
    final PsiElement elt = context.getPsiLocation();
    if (elt instanceof PsiDirectory) {
      return setupForDirectory(config, (PsiDirectory)elt);
    }

    final DartFile file = FlutterRunConfigurationProducer.getDartFile(context);
    if (file == null) {
      return false;
    }

    final String testName = findTestName(elt);
    if (testName != null) {
      return setupForSingleTest(config, context, file, testName);
    }

    return setupForDartFile(config, context, file);
  }

  /**
   * Returns the name of the test containing this element, or null if it can't be calculated.
   */
  private String findTestName(@Nullable PsiElement elt) {
    if (elt == null) return null;

    final DartCallExpression call = DartSyntax.findEnclosingFunctionCall(elt, "test");
    if (call == null) return null;

    final DartStringLiteralExpression lit = DartSyntax.getArgument(call, 0, DartStringLiteralExpression.class);
    if (lit == null) return null;

    return DartSyntax.unquote(lit);
  }

  private boolean setupForSingleTest(TestConfig config, ConfigurationContext context, DartFile file, String testName) {
    final VirtualFile testFile = verifyFlutterTestFile(config, context, file);
    if (testFile == null) return false;

    config.setFields(TestFields.forTestName(testName, testFile.getPath()));
    config.setGeneratedName();

    return true;
  }

  private boolean setupForDartFile(TestConfig config, ConfigurationContext context, DartFile file) {
    final VirtualFile testFile = verifyFlutterTestFile(config, context, file);
    if (testFile == null) return false;

    config.setFields(TestFields.forFile(testFile.getPath()));
    config.setGeneratedName();

    return true;
  }

  private VirtualFile verifyFlutterTestFile(TestConfig config, ConfigurationContext context, DartFile file) {
    final PubRoot root = PubRoot.forPsiFile(file);
    if (root == null) return null;

    if (!FlutterModuleUtils.isFlutterModule(context.getModule())) return null;

    final VirtualFile candidate = FlutterRunConfigurationProducer.getFlutterEntryFile(context, false);
    if (candidate == null) return null;

    final String relativePath = root.getRelativePath(candidate);
    if (relativePath == null || !relativePath.startsWith("test/")) return null;

    return candidate;
  }

  private boolean setupForDirectory(TestConfig config, PsiDirectory dir) {
    final PubRoot root = PubRoot.forDescendant(dir.getVirtualFile(), dir.getProject());
    if (root == null) return false;
    
    if (!FlutterModuleUtils.hasFlutterModule(dir.getProject())) return false;

    if (!root.hasTests(dir.getVirtualFile())) return false;

    config.setFields(TestFields.forDir(dir.getVirtualFile().getPath()));
    config.setGeneratedName();
    return true;
  }

  /**
   * Returns true if a run config was already created for this file. If so we will reuse it.
   */
  @Override
  public boolean isConfigurationFromContext(TestConfig config, ConfigurationContext context) {
    final VirtualFile fileOrDir = config.getFields().getFileOrDir();
    if (fileOrDir == null) return false;

    final PsiElement target = context.getPsiLocation();
    if (target instanceof PsiDirectory) {
      return ((PsiDirectory)target).getVirtualFile().equals(fileOrDir);
    }

    if (!FlutterRunConfigurationProducer.hasDartFile(context, fileOrDir.getPath())) return false;

    final String testName = findTestName(context.getPsiLocation());
    if (config.getFields().getScope() == TestFields.Scope.NAME) {
      if (testName == null || !testName.equals(config.getFields().getTestName())) return false;
    } else {
      if (testName != null) return false;
    }

    return true;
  }

  @Override
  public boolean shouldReplace(@NotNull ConfigurationFromContext self, @NotNull ConfigurationFromContext other) {
    return DartPlugin.isDartTestConfiguration(other.getConfigurationType());
  }
}