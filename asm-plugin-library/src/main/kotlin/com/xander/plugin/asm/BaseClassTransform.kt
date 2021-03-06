package com.xander.plugin.asm

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.ide.common.internal.WaitableExecutor
import com.android.ide.common.workers.ExecutorServiceAdapter
import com.google.common.io.Files
import com.xander.plugin.asm.lib.*
import com.xander.plugin.asm.lib.URLClassLoaderHelper.getClassLoader
import groovy.lang.Reference
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ForkJoinPool

open abstract class BaseClassTransform(protected val project: Project) : Transform() {

  private var pluginConfig = PluginConfig.debug

  private var weaver: IWeaverFactory

  private val waitableExecutor: WaitableExecutor = WaitableExecutor.useGlobalSharedThreadPool()

  private val executorFacade: ExecutorServiceAdapter = ExecutorServiceAdapter(
      name,
      name,
      ForkJoinPool.commonPool()
  )

  override fun getName(): String {
    return this.javaClass.simpleName
  }

  override fun getInputTypes(): Set<QualifiedContent.ContentType> {
    return TransformManager.CONTENT_CLASS
  }

  override fun getScopes(): MutableSet<in QualifiedContent.Scope>? {
    return SCOPES
  }

  override fun isIncremental(): Boolean {
    return true
  }

  override fun isCacheable(): Boolean {
    return true
  }

  abstract fun createWeaver(): BaseWeaverFactory

  private fun createPluginConfig(): PluginConfig {
    return project.extensions.getByName(getConfigName()) as PluginConfig
  }

  abstract fun getConfigName(): String

  companion object {
    private val SCOPES: MutableSet<QualifiedContent.Scope> = HashSet()

    init {
      SCOPES.add(QualifiedContent.Scope.PROJECT)
      SCOPES.add(QualifiedContent.Scope.SUB_PROJECTS)
      SCOPES.add(QualifiedContent.Scope.EXTERNAL_LIBRARIES)
    }
  }

  init {
    weaver = createWeaver()
    project.extensions.create(getConfigName(), PluginConfig::class.java)
  }

  private fun applyConfig(config: PluginConfig) {
    BaseWeaverFactory.pluginConfig = config
    BaseClassVisitor.pluginConfig = config
    TimeMethodVisitor.pluginConfig = config
  }

  override fun transform(transformInvocation: TransformInvocation) {
    //super.transform(transformInvocation)
    val skip = Reference(false)
    println("plugin start ====================================================================")
    pluginConfig = createPluginConfig()
    println("$name config:$pluginConfig")
    applyConfig(pluginConfig)
    val variantName = transformInvocation.context.variantName
    if ("debug" == variantName) {
      skip.set(pluginConfig.debugSkip)
    } else if ("release" == variantName) {
      skip.set(pluginConfig.releaseSkip)
    }
    println("name: $name, variant:$variantName, skip:${skip.get()}")
    val startTime = System.currentTimeMillis()
    val outputProvider = transformInvocation.outputProvider
    if (!transformInvocation.isIncremental) {
      outputProvider.deleteAll()
    }
    val inputs = transformInvocation.inputs
    val referencedInputs = transformInvocation.referencedInputs
    val urlClassLoader = getClassLoader(inputs, referencedInputs, project)
    weaver.setClassLoader(urlClassLoader)
    var flagForCleanDexBuilderFolder = false
    for (input in inputs) {
      for (jarInput in input.jarInputs) {
        val dest = outputProvider.getContentLocation(
            jarInput.file.absolutePath,
            jarInput.contentTypes,
            jarInput.scopes,
            Format.JAR
        )
        if (pluginConfig.log) {
          println("jarInput:${jarInput.file.absolutePath} ,dest:${dest.absolutePath}")
        }
        if (skip.get() || pluginConfig.skipJar) {
          println("skip transform jar: ${jarInput.file.absolutePath}")
          FileUtils.copyFile(jarInput.file, dest)
          continue
        }
        val status = jarInput.status
        if (transformInvocation.isIncremental) {
          when (status) {
            Status.NOTCHANGED -> {
            }
            Status.ADDED, Status.CHANGED -> transformJar(jarInput.file, dest)
            Status.REMOVED -> if (dest.exists()) FileUtils.forceDelete(dest)
          }
        } else {
          // Forgive me!, Some project will store 3rd-party aar for serveral copies
          // in dexbuilder folder,unknown issue.
          if (inDuplicatedClassSafeMode() && !flagForCleanDexBuilderFolder) {
            cleanDexBuilderFolder(dest)
            flagForCleanDexBuilderFolder = true
          }
          if (pluginConfig.useExecutor) {
            waitableExecutor.execute {
              transformJar(jarInput.file, dest)
            }
          } else {
            transformJar(jarInput.file, dest)
          }
        }
      }
      for (directoryInput in input.directoryInputs) {
        val dest = outputProvider.getContentLocation(
            directoryInput.name,
            directoryInput.contentTypes,
            directoryInput.scopes,
            Format.DIRECTORY
        )
        if (pluginConfig.log) {
          println("directoryInput:${directoryInput.file.absolutePath},dest:${dest.absolutePath}")
        }
        FileUtils.forceMkdir(dest)
        if (skip.get()) {
          println("skip transform dir:${directoryInput.file.absolutePath}")
          FileUtils.copyDirectory(directoryInput.file, dest)
          continue
        }
        if (transformInvocation.isIncremental) {
          val srcDirPath = directoryInput.file.absolutePath
          val destDirPath = dest.absolutePath
          val fileStatusMap = directoryInput.changedFiles
          for ((inputFile, status) in fileStatusMap) {
            val destFilePath = inputFile.absolutePath.replace(srcDirPath, destDirPath)
            val destFile = File(destFilePath)
            when (status) {
              Status.NOTCHANGED -> {
              }
              Status.REMOVED -> if (destFile.exists()) {
                destFile.delete()
              }
              Status.ADDED, Status.CHANGED -> {
                try {
                  FileUtils.touch(destFile)
                } catch (e: IOException) {
                  //maybe mkdirs fail for some strange reason, try again.
                  Files.createParentDirs(destFile)
                }
                if (pluginConfig.useExecutor) {
                  waitableExecutor.execute {
                    transformSingleFile(inputFile, destFile, srcDirPath)
                  }
                } else {
                  transformSingleFile(inputFile, destFile, srcDirPath)
                }
              }
            }
          }
        } else {
          transformDir(directoryInput.file, directoryInput.file.absolutePath, dest.absolutePath)
        }
      }
    }
    if (pluginConfig.useExecutor) {
      try {
        waitableExecutor.waitForTasksWithQuickFail<Void>(false)
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
    val costTime = System.currentTimeMillis() - startTime
    println("plugin: $name, cost time: $costTime ms")
  }

  protected fun transformSingleFile(inputFile: File, outputFile: File, srcBaseDir: String) {
    if (pluginConfig.log) {
      println("transformSingleFile inputFile:${inputFile.absolutePath}")
      println("transformSingleFile outputFile:${outputFile.absolutePath}")
    }
    waitableExecutor.execute {
      weaver.weaveSingleClass(inputFile, outputFile, srcBaseDir)
    }
  }

  protected fun transformDir(sourceDir: File, inputDirPath: String, outputDirPath: String) {
    if (sourceDir.isDirectory) {
      val files = sourceDir.listFiles()
      if (null == files || files.isEmpty()) {
        return
      }
      val childFiles = ArrayList<File>()
      for (sourceFile in files) {
        if (sourceFile.isDirectory) {
          transformDir(sourceFile, inputDirPath, outputDirPath)
        } else {
          childFiles.add(sourceFile)
        }
      }
      // 一次处理一个文件夹下面的文件，防止创建的任务过多
      if (childFiles.isNotEmpty()) {
        if (pluginConfig.useExecutor) {
          waitableExecutor.execute {
            transformFileList(childFiles, inputDirPath, outputDirPath)
          }
        } else {
          transformFileList(childFiles, inputDirPath, outputDirPath)
        }
      }
    }
  }

  protected fun transformFileList(sourceList: ArrayList<File>, inputDirPath: String, outputDirPath: String) {
    if (pluginConfig.log) {
      println("transformFileList inputDirPath:${inputDirPath}")
      println("transformFileList outputDirPath:${outputDirPath}")
    }
    for (sourceFile in sourceList) {
      val sourceFilePath = sourceFile.absolutePath
      val outputFile = File(sourceFilePath.replace(inputDirPath, outputDirPath))
      if (pluginConfig.log) {
        println("transformFileList sourceFile:${sourceFile.absolutePath}")
        println("transformFileList outputFile:${outputFile.absolutePath}")
      }
      weaver.weaveSingleClass(sourceFile, outputFile, inputDirPath)
    }
  }

  protected fun transformJar(srcJar: File, destJar: File) {
    if (pluginConfig.log) {
      println("transformJar srcJar:${srcJar.absolutePath}")
      println("transformJar destJar:${destJar.absolutePath}")
    }
    weaver.weaveJar(srcJar, destJar)
  }

  protected fun cleanDexBuilderFolder(dest: File) {
    try {
      val dexBuilderDir = replaceLastPart(dest.absolutePath, name, "dexBuilder")
      //intermediates/transforms/dexBuilder/debug
      val file = File(dexBuilderDir).parentFile
      println("clean dexBuilder folder:${file.absolutePath}")
      if (file.exists() && file.isDirectory) {
        FileUtils.deleteDirectory(file)
        //com.android.utils.FileUtils.deleteDirectoryContents(file)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  protected fun replaceLastPart(originString: String, replacement: String, toReplace: String): String {
    val start = originString.lastIndexOf(replacement)
    val builder = StringBuilder()
    builder.append(originString.substring(0, start))
    builder.append(toReplace)
    builder.append(originString.substring(start + replacement.length))
    return builder.toString()
  }

  protected open fun inDuplicatedClassSafeMode(): Boolean {
    return false
  }
}