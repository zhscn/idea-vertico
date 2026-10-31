package com.github.zhscn.vertico

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.io.IOException
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class FindFilePopup(private val project: Project, start: Path) : Disposable {
    private val model = FindFileModel(Path.of(System.getProperty("user.home")), start)
    private val input = JTextField(model.abbreviate(start))
    private val rows = DefaultListModel<Entry>()
    private val list = JBList(rows)
    private val status = JBLabel()
    private lateinit var popup: JBPopup

    private var listedDirectory: Path? = null
    private var listed: List<Entry> = emptyList()
    private var generation = 0

    fun show() {
        input.focusTraversalKeysEnabled = false
        list.isFocusable = false
        list.visibleRowCount = 12
        list.cellRenderer = EntryRenderer { model.patternOf(input.text) }
        status.foreground = UIUtil.getContextHelpForeground()
        status.border = JBUI.Borders.empty(2, 6)

        val panel = JPanel(BorderLayout()).apply {
            preferredSize = JBDimension(720, 340)
            add(input, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, input)
            .setTitle("Find File")
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        Disposer.register(popup, this)

        bindKeys()
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })
        changed()
        popup.showCenteredInCurrentWindow(project)
    }

    private fun bindKeys() {
        bind("ENTER") { submit(model.submit(input.text, selectedEntry())) }
        bind("alt ENTER", "ctrl ENTER") { submit(model.submitInput(input.text)) }
        bind("TAB") { selectedEntry()?.let { input.text = model.complete(input.text, it) } }
        bind("ctrl N", "DOWN") { move(1) }
        bind("ctrl P", "UP") { move(-1) }
        bind("ctrl G") { popup.cancel() }
        bind("BACK_SPACE") { backspace() }
        bind("alt BACK_SPACE", "ctrl BACK_SPACE") { input.text = model.deleteWord(input.text) }
    }

    private fun bind(vararg keys: String, handler: () -> Unit) {
        DumbAwareAction.create { handler() }
            .registerCustomShortcutSet(CustomShortcutSet.fromString(*keys), input as JComponent, popup)
    }

    private fun backspace() {
        val text = input.text
        when {
            input.selectedText != null -> input.replaceSelection("")
            input.caretPosition == text.length && text.endsWith("/") -> input.text = model.parentOf(text)
            input.caretPosition > 0 -> input.document.remove(input.caretPosition - 1, 1)
        }
    }

    private fun move(delta: Int) {
        if (rows.isEmpty) return
        ScrollingUtil.selectItem(list, Math.floorMod(list.selectedIndex + delta, rows.size()))
    }

    private fun selectedEntry(): Entry? = list.selectedValue

    private fun changed() {
        val text = input.text
        val tidied = model.tidy(text)
        if (tidied != text) {
            SwingUtilities.invokeLater { input.text = tidied }
            return
        }
        val directory = model.directoryOf(text)
        if (directory != listedDirectory) {
            listedDirectory = directory
            listed = emptyList()
            render()
            reload(directory)
        } else {
            render()
        }
    }

    private fun reload(directory: Path?) {
        val current = ++generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = directory?.let(::listDirectory).orEmpty()
            ApplicationManager.getApplication().invokeLater({
                if (current == generation) {
                    listed = entries
                    render()
                }
            }, ModalityState.any())
        }
    }

    private fun render() {
        val pattern = model.patternOf(input.text)
        val matches = Matcher.filter(listed, pattern)
        rows.clear()
        rows.addAll(matches)
        if (matches.isNotEmpty()) list.selectedIndex = 0
        status.text = when {
            matches.isEmpty() && input.text.isNotEmpty() && pattern.isNotEmpty() -> "No match: RET creates $pattern"
            matches.isEmpty() -> "Empty directory: type a name to create it"
            else -> "${matches.size} of ${listed.size}   RET open   TAB complete   M-RET use input"
        }
    }

    private fun submit(action: Submit) {
        when (action) {
            is Submit.Descend -> input.text = model.abbreviate(action.directory)
            is Submit.Open -> open(action.file)
            is Submit.Create -> create(action)
            Submit.Nothing -> Unit
        }
    }

    private fun open(path: Path) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (file == null) {
            status.text = "Cannot open $path"
            return
        }
        popup.closeOk(null)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun create(action: Submit.Create) {
        try {
            val created = createPath(project, action.path, action.isDirectory)
            if (action.isDirectory) {
                input.text = model.abbreviate(action.path)
            } else {
                popup.closeOk(null)
                FileEditorManager.getInstance(project).openFile(created, true)
            }
        } catch (e: IOException) {
            status.text = "Cannot create ${action.path}: ${e.message}"
        }
    }

    override fun dispose() {
        generation++
    }

    private class EntryRenderer(private val pattern: () -> String) : ColoredListCellRenderer<Entry>() {
        override fun customizeCellRenderer(
            list: JList<out Entry>,
            value: Entry,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = if (value.isDirectory) {
                AllIcons.Nodes.Folder
            } else {
                FileTypeManager.getInstance().getFileTypeByFileName(value.name).icon
            }
            val base = if (value.name.startsWith(".")) {
                SimpleTextAttributes.GRAYED_ATTRIBUTES
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            val match = base.derive(base.style or SimpleTextAttributes.STYLE_SEARCH_MATCH, null, null, null)
            var position = 0
            for (span in Matcher.highlights(value.name, pattern())) {
                if (span.first > position) append(value.label.substring(position, span.first), base)
                append(value.label.substring(span.first, span.last + 1), match)
                position = span.last + 1
            }
            if (position < value.label.length) append(value.label.substring(position), base)
        }
    }
}

/** Creates [path] and any missing parents as one undoable command. */
@Throws(IOException::class)
internal fun createPath(project: Project, path: Path, isDirectory: Boolean): VirtualFile =
    WriteCommandAction.writeCommandAction(project)
        .withName("Create ${path.fileName}")
        .compute<VirtualFile, IOException> {
            if (isDirectory) {
                VfsUtil.createDirectoryIfMissing(path.toString())
                    ?: throw IOException("cannot create directory")
            } else {
                val parent = VfsUtil.createDirectoryIfMissing(path.parent.toString())
                    ?: throw IOException("cannot create ${path.parent}")
                parent.createChildData(FindFilePopup::class.java, path.fileName.toString())
            }
        }
