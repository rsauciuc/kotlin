/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.nodejs

import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.util.ScriptFileUtil
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.javascript.debugger.execution.DebuggableProcessRunConfigurationBase
import com.intellij.javascript.nodejs.execution.findNodeRunConfigurationLocationFilter
import com.intellij.javascript.testFramework.PreferableRunConfiguration
import com.intellij.lang.javascript.modules.NodeModuleUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.project.isDirectoryBased
import com.intellij.psi.PsiElement
import com.intellij.util.ThreeState
import com.jetbrains.nodejs.run.NodeJsRunConfiguration
import com.jetbrains.nodejs.run.NodeJsRunConfigurationType

class NodeJsRunConfigurationProducer : RunConfigurationProducer<NodeJsRunConfiguration>(NodeJsRunConfigurationType.getInstance()) {
  private val ConfigurationContext?.isAcceptable: Boolean
    get() {
      val original = this?.getOriginalConfiguration(null)
      return original == null || original is NodeJsRunConfiguration
    }

  override fun setupConfigurationFromContext(configuration: NodeJsRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    if (!context.isAcceptable) {
      return false
    }

    val psiFile = sourceElement.get()?.containingFile ?: return false
    val virtualFile = psiFile.virtualFile ?: return false
    val project = psiFile.project
    if (project.isDirectoryBased && project.service<RcProducerManager>().setupConfigurationFromContext(virtualFile, configuration, psiFile, sourceElement, context) == ThreeState.YES) {
      return true
    }

    findNodeRunConfigurationLocationFilter(psiFile.language.associatedFileType ?: virtualFile.fileType) ?: return false

    sourceElement.set(psiFile)

    configuration.name = virtualFile.name
    val dir = virtualFile.parent
    if (dir != null && dir.isInLocalFileSystem && isDefaultWorkingDirEmpty(project) && ScratchFileService.getInstance().getRootType(virtualFile) == null) {
      configuration.setWorkingDirectory(dir.path)
      configuration.inputPath = virtualFile.name
    }
    else {
      configuration.inputPath = ScriptFileUtil.getScriptFilePath(virtualFile)
    }
    configuration.addCoffeeScriptNodeOptionIfNeeded()

    return true
  }

  override fun isConfigurationFromContext(configuration: NodeJsRunConfiguration, context: ConfigurationContext): Boolean {
    if (!context.isAcceptable) {
      return false
    }

    val location = context.location ?: return false
    val file = location.virtualFile ?: return false
    val project = location.project
    if (file.isInLocalFileSystem && project.isDirectoryBased) {
      val result = project.service<RcProducerManager>().isConfigurationFromContext(file, configuration, location)
      if (result != ThreeState.UNSURE) {
        return result.toBoolean()
      }
    }

    return ScriptFileUtil.getScriptFilePath(file) == configuration.inputPath || file == DebuggableProcessRunConfigurationBase.findInputVirtualFile(configuration)
  }

  override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext?): Boolean {
    val psiFile = self.sourceElement.containingFile
    if (psiFile != null && other != null) {
      val otherRc = other.configuration as? PreferableRunConfiguration
      if (otherRc != null && otherRc.isPreferredOver(self.configuration, psiFile)) {
        return shouldReplace(self, other)
      }
    }
    return psiFile != null && NodeModuleUtil.isModuleFile(psiFile)
  }

  override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
    if (other.configuration is NodeJsRunConfiguration) {
      return false
    }

    val psiFile = self.sourceElement.containingFile
    val file = psiFile.virtualFile ?: return false
    val project = psiFile.project
    return project.service<RcProducerManager>().isConfigurationFromContext(file, self.configuration as NodeJsRunConfiguration, PsiLocation(self.sourceElement)) == ThreeState.YES
  }

  override fun onFirstRun(configuration: ConfigurationFromContext, context: ConfigurationContext, startRunnable: Runnable) {
    configureBeforeRun(configuration, context)
    super.onFirstRun(configuration, context, startRunnable)
  }

  private fun configureBeforeRun(configuration: ConfigurationFromContext, context: ConfigurationContext) {
    val location = context.location ?: return
    val file = location.virtualFile ?: return
    val project = location.project
    if (file.isInLocalFileSystem && project.isDirectoryBased) {
      project.service<RcProducerManager>().configureBeforeFirstRun(file, configuration, context)
    }
  }

  private fun isDefaultWorkingDirEmpty(project: Project): Boolean {
    val defaultConfiguration = NodeJsRunConfiguration.getDefaultRunConfiguration(project)
    return defaultConfiguration != null && defaultConfiguration.workingDirectory.isEmpty()
  }
}
