package com.github.zhscn.vertico

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import java.nio.file.Path

class FindFileAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isInLocalFileSystem }
        val start = file?.let { if (it.isDirectory) it else it.parent }?.toNioPath()
            ?: project.basePath?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"))
        FindFilePopup(project, start).show()
    }
}
