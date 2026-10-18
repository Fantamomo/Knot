package com.fantamomo.know.clishared

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.RollingPolicyBase
import org.slf4j.LoggerFactory
import java.nio.file.Path

object LoggerManager {
    private val rootLogger: Logger

    init {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext

        rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
    }

    fun detachConsoleAppender() {
        rootLogger.detachAppender("CONSOLE")
    }

    fun changeFileAppenderOutput(baseDir: Path) {
        val iterator = rootLogger.iteratorForAppenders()

        for (appender in iterator) {
            when (appender) {
                is RollingFileAppender<*> -> {
                    val rollingPolicy = appender.rollingPolicy as? RollingPolicyBase
                        ?: continue

                    val currentFile = appender.file
                    val currentPattern = rollingPolicy.fileNamePattern

                    if (currentFile == null) continue

                    val newFile = baseDir.resolve(currentFile)

                    val newPattern = baseDir.resolve(currentPattern)

                    appender.stop()

                    appender.file = newFile.toString()
                    rollingPolicy.fileNamePattern = newPattern.toString()
                    rollingPolicy.start()

                    appender.start()

                    break
                }

                is FileAppender<*> -> {
                    val currentFile = appender.file ?: continue

                    val newFile = baseDir.resolve(currentFile)

                    newFile.parent?.toFile()?.mkdirs()

                    appender.stop()
                    appender.file = newFile.toString()
                    appender.start()

                    break
                }
            }
        }
    }}