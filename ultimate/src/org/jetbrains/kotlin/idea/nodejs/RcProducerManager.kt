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

import com.intellij.codeInspection.SmartHashMap
import com.intellij.execution.Location
import com.intellij.execution.RunManagerEx
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.internal.statistic.UsageTrigger
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.typescript.compiler.action.before.TypeScriptCompileBeforeRunTask
import com.intellij.lang.typescript.compiler.action.before.TypeScriptCompileBeforeRunTaskProvider
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.project.stateStore
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.DocumentUtil
import com.intellij.util.SmartList
import com.intellij.util.ThreeState
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.io.inputStreamIfExists
import com.intellij.util.text.minimatch.minimatchAll
import com.intellij.util.text.nullize
import com.jetbrains.nodejs.run.NodeJsRunConfiguration
import com.jetbrains.nodejs.run.RcProducerConfiguration
import com.jetbrains.nodejs.run.parseRcProducer
import java.nio.file.Paths
import java.util.regex.Pattern

private val LOG = logger<RcProducerManager>()

private val MACRO_PATTERN = Regex("\\$\\{([a-zA-Z0-9]+)\\}")

val notificationGroup by lazy { NotificationGroup.balloonGroup("NodeJS Run Configuration Producer") }

class RcProducerInvalidConfigurationException(message: String) : RuntimeException(message)

class RcProducerManager(private val project: Project) : SimpleModificationTracker() {
    private var configurations: List<RcProducerConfiguration>? = null
    var configurationFileExists = ThreeState.UNSURE

    private val configurationFilePath = "${project.stateStore.directoryStorePath!!}/rc-producer.yml"
    private val basePath = project.basePath!!
    private val projectRootFile = project.baseDir

    init {
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES,
                                               object : BulkFileListener {
                                                   override fun after(events: List<VFileEvent>) {
                                                       for (event in events) {
                                                           if (configurationFilePath == event.path) {
                                                               incModificationCount()
                                                               configurations = null
                                                               configurationFileExists = ThreeState.UNSURE
                                                               return
                                                           }
                                                       }
                                                   }
                                               })
    }

    fun isApplicable(psiFile: PsiFile, isLineSpecific: Boolean): Boolean {
        processProducers(psiFile.virtualFile ?: return false, project) {
            if ((it.lineRegExp != null) == isLineSpecific) {
                return true
            }
        }
        return false
    }

    // producers with lineRegExp will be first
    private fun getConfigurations(project: Project): List<RcProducerConfiguration>? {
        var list = configurations
        if (list == null && configurationFileExists == ThreeState.UNSURE) {
            val input = Paths.get(configurationFilePath)?.inputStreamIfExists()
            if (input == null) {
                configurationFileExists = ThreeState.NO
                return null
            }
            else {
                configurationFileExists = ThreeState.YES
            }

            input.use {
                try {
                    list = parseRcProducer(it, project).sortedBy { if (it.lineRegExp == null) 1 else 0 }
                    UsageTrigger.trigger("node.js.rcProducer.config.parsed")
                }
                catch (e: Exception) {
                    notificationGroup.createNotification("NodeJS Run Configuration Producer", "Cannot parse rc-producer.yml: ${e.message}", NotificationType.ERROR, null).notify(project)
                    LOG.error(e)
                }
            }
            configurations = list
        }
        return list
    }

    private inline fun processProducers(file: VirtualFile, project: Project, processor: (RcProducerConfiguration) -> Unit) {
        val list = getConfigurations(project) ?: return
        val path = buildPath(file)
        for (producer in list) {
            if (minimatchAll(path, producer.files)) {
                processor(producer)
            }
        }
    }

    private fun buildPath(file: VirtualFile): SmartList<CharSequence> {
        val path = SmartList<CharSequence>(file.nameSequence)
        var parent = file.parent
        // pattern is not forced to use leading ** - "test/**/*" it is allowed and supported (i.e. test is a directory in the project root)
        while (parent != null && parent != projectRootFile) {
            path.add(parent.nameSequence)
            parent = parent.parent
        }

        path.reverse()
        return path
    }

    private fun process(file: VirtualFile, location: Location<PsiElement>, task: (producer: RcProducerConfiguration, location: Location<*>, matches: Map<String, String>?, argsTransform: (String) -> CharSequence, workingDir: String, script: String) -> Boolean): ThreeState {
        val project = location.project
        processProducers(file, project) { producer ->
            val matches = if (producer.lineRegExp == null) {
                null
            }
            else {
                matchLine(producer.lineRegExp!!, location) ?: return@processProducers
            }

            val workingDir = producer.workingDirectory?.let { Paths.get(it).resolve(Paths.get(basePath)).toString() } ?: basePath
            var filePath: String? = null
            val fileNameWithoutExt = file.nameWithoutExtension
            val argsTransform: (String) -> CharSequence = {
                it
                        .replace(MACRO_PATTERN) {
                            val macroName = it.groupValues[1]
                            when (macroName) {
                                "file" -> {
                                    if (filePath == null) {
                                        filePath = file.path
                                    }
                                    filePath!!
                                }
                                "fileNameWithoutExt" -> fileNameWithoutExt
                                else -> matches?.get(macroName) ?: throw RcProducerInvalidConfigurationException("Unknown macro: ${macroName}")
                            }
                        }
            }

            return ThreeState.fromBoolean(task(producer, location, matches, argsTransform, workingDir,
                                               producer.script ?: FileUtilRt.getRelativePath(workingDir, filePath ?: file.path, '/')!!))
        }
        return ThreeState.UNSURE
    }

    fun setupConfigurationFromContext(file: VirtualFile,
                                      configuration: NodeJsRunConfiguration,
                                      psiFile: PsiFile,
                                      sourceElement: Ref<PsiElement>,
                                      context: ConfigurationContext): ThreeState {
        return process(file, context.location ?: return ThreeState.UNSURE) { producer, location, matches, argsTransform, workingDir, script ->
            if (producer.lineRegExp == null) {
                sourceElement.set(psiFile)
            }
            else {
                sourceElement.set(context.psiLocation?.let { PsiTreeUtil.findFirstParent(it, { it is JSCallExpression || it is JSFunction }) } ?: psiFile)
            }

            configuration.name = producer.rcName?.let { argsTransform(it).toString() } ?: file.name
            configuration.setWorkingDirectory(workingDir)
            configuration.inputPath = script
            configuration.applicationParameters = ParametersListUtil.join(producer.scriptArgs.map(argsTransform))
            configuration.programParameters = ParametersListUtil.join(producer.nodeArgs.map(argsTransform))
            configuration.envs = producer.env

            // we configure before run on first run otherwise RunManagerImpl.myConfigurationToBeforeTasksMap will be spammed
            true
        }
    }

    fun isConfigurationFromContext(file: VirtualFile, configuration: NodeJsRunConfiguration, location: Location<PsiElement>): ThreeState {
        return process(file, location) { producer, location, matches, argsTransform, workingDir, script ->
            configuration.workingDirectory == workingDir
            && configuration.inputPath == script
            && configuration.applicationParameters == ParametersListUtil.join(producer.scriptArgs.map(argsTransform)).nullize()
            && configuration.programParameters == ParametersListUtil.join(producer.nodeArgs.map(argsTransform)).nullize()
            && configuration.envs == producer.env
        }
    }

    fun configureBeforeFirstRun(file: VirtualFile, configuration: ConfigurationFromContext, context: ConfigurationContext) {
        process(file, context.location ?: return) { producer, location, matches, argsTransform, workingDir, script ->
            UsageTrigger.trigger("node.js.rcProducer.config.firstRun")
            for (taskProvider in producer.beforeRun) {
                val rc = configuration.configuration
                val task = taskProvider.createTask(rc) ?: throw RcProducerInvalidConfigurationException(
                        if (taskProvider.id == TypeScriptCompileBeforeRunTaskProvider.ID)
                            "Please ensure that Settings -> Languages & Frameworks -> TypeScript -> Use TypeScript Service is enabled"
                        else "Created before run task by ${taskProvider.name} is null, probably incorrect configuration")

                if (task is TypeScriptCompileBeforeRunTask) {
                    task.configPath = null
                }
                task.isEnabled = true
                RunManagerEx.getInstanceEx(context.project).setBeforeRunTasks(rc, listOf(task), false)
            }

            true
        }
    }

    private fun matchLine(lineRegExp: Pattern, location: Location<PsiElement>): Map<String, String>? {
        val document = PsiDocumentManager.getInstance(location.project).getDocument(location.psiElement.containingFile) ?: return null
        val lineRange = DocumentUtil.getLineTextRange(document, document.getLineNumber(location.psiElement.textOffset))
        val matcher = lineRegExp.matcher(document.immutableCharSequence.subSequence(lineRange.startOffset, lineRange.endOffset))
        if (!matcher.find()) {
            return null
        }

        val matches = SmartHashMap<String, String>()
        for (i in 1..matcher.groupCount()) {
            val index = i - 1
            matches.put("$index", matcher.group(i))
            matches.put("${index}regExp", "${escapeToRegexp(matcher.group(i))}$")
        }
        return matches
    }
}

fun escapeToRegexp(text: String): CharSequence {
    var builder: StringBuilder? = null
    for (i in 0..text.length - 1) {
        val c = text.get(i)
        if (".?*+^$[](){}|-\\".contains(c)) {
            if (builder == null) {
                builder = StringBuilder()
                builder.append(text, 0, i)
            }
            builder.append('\\')
        }

        builder?.apply {
            append(c)
        }
    }
    return builder ?: text
}