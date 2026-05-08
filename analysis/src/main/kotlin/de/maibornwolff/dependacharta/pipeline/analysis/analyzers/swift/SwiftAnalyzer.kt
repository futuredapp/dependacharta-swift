package de.maibornwolff.dependacharta.pipeline.analysis.analyzers.swift

import de.maibornwolff.dependacharta.pipeline.analysis.analyzers.LanguageAnalyzer
import de.maibornwolff.dependacharta.pipeline.analysis.model.Dependency
import de.maibornwolff.dependacharta.pipeline.analysis.model.FileInfo
import de.maibornwolff.dependacharta.pipeline.analysis.model.FileReport
import de.maibornwolff.dependacharta.pipeline.analysis.model.Node
import de.maibornwolff.dependacharta.pipeline.analysis.model.NodeType
import de.maibornwolff.dependacharta.pipeline.analysis.model.Path
import de.maibornwolff.dependacharta.pipeline.analysis.model.Type
import de.maibornwolff.dependacharta.pipeline.shared.SupportedLanguage

class SwiftAnalyzer(
    private val fileInfo: FileInfo,
) : LanguageAnalyzer {
    override fun analyze(): FileReport {
        val code = removeCommentsAndStrings(fileInfo.content)
        val declarations = extractDeclarations(code, fileInfo.physicalPath)
        val globalTargetVisibility = setOf(Dependency(Path.empty(), isWildcard = true))

        return FileReport(
            declarations.map { declaration ->
                val usedTypes = extractUsedTypes(declaration.source, setOf(declaration.name))
                Node(
                    pathWithName = pathFor(declaration.name),
                    physicalPath = fileInfo.physicalPath,
                    nodeType = declaration.nodeType,
                    language = SupportedLanguage.SWIFT,
                    dependencies = globalTargetVisibility,
                    usedTypes = usedTypes
                )
            }
        )
    }

    private fun pathFor(name: String): Path {
        val directoryParts = fileInfo.physicalPath
            .replace("\\", "/")
            .split("/")
            .dropLast(1)
            .filter { it.isNotBlank() }

        return Path(directoryParts + name)
    }

    private fun extractDeclarations(
        code: String,
        physicalPath: String
    ): List<Declaration> {
        val declarations = declarationRegex
            .findAll(code)
            .map {
                Declaration(
                    name = it.groupValues[2],
                    nodeType = nodeTypeFor(it.groupValues[1]),
                    source = extractDeclarationSource(code, it.range.first)
                )
            }.toList()

        val fileName = physicalPath.substringAfterLast("/").substringBefore(".swift")
        val firstFileNamePart = fileName.substringBefore("+")
        val extensions = extensionRegex
            .findAll(code)
            .map {
                Declaration(
                    name = extensionNodeName(fileName, it.groupValues[1]),
                    nodeType = NodeType.UNKNOWN,
                    source = extractDeclarationSource(code, it.range.first)
                )
            }.toList()

        val projectExtensions = extensions.filter {
            it.name == firstFileNamePart ||
                it.name == fileName ||
                (fileName.contains("+") && fileName.startsWith("${it.extendedTypeName}+"))
        }

        return (declarations + projectExtensions).distinctBy { it.name }
    }

    private fun extensionNodeName(
        fileName: String,
        extendedTypeName: String
    ): String {
        return if (fileName.startsWith("$extendedTypeName+")) fileName else extendedTypeName
    }

    private fun extractDeclarationSource(
        code: String,
        startIndex: Int
    ): String {
        val openingBraceIndex = code.indexOf('{', startIndex)
        if (openingBraceIndex == -1) return code.substring(startIndex)

        val closingBraceIndex = findMatchingBrace(code, openingBraceIndex) ?: return code.substring(startIndex)
        return code.substring(startIndex, closingBraceIndex + 1)
    }

    private fun findMatchingBrace(
        code: String,
        openingBraceIndex: Int
    ): Int? {
        var depth = 0
        for (index in openingBraceIndex until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun nodeTypeFor(keyword: String): NodeType =
        when (keyword) {
            "class", "actor" -> NodeType.CLASS
            "struct" -> NodeType.VALUECLASS
            "protocol" -> NodeType.INTERFACE
            "enum" -> NodeType.ENUM
            else -> NodeType.UNKNOWN
        }

    private fun extractUsedTypes(
        code: String,
        declaredNames: Set<String>
    ): Set<Type> {
        val importedModules = importRegex.findAll(code).map { it.groupValues[1] }.toSet()
        return typeIdentifierRegex
            .findAll(code)
            .map { it.value }
            .filter { it !in declaredNames }
            .filter { it !in swiftKeywords }
            .filter { it !in importedModules }
            .filter { it.length > 1 }
            .map { Type.simple(it) }
            .toSet()
    }

    private fun removeCommentsAndStrings(code: String): String =
        code
            .replace(multilineCommentRegex, " ")
            .replace(lineCommentRegex, " ")
            .replace(multilineStringRegex, "\"\"")
            .replace(stringRegex, "\"\"")

    private data class Declaration(
        val name: String,
        val nodeType: NodeType,
        val source: String,
    ) {
        val extendedTypeName: String
            get() = name.substringBefore("+")
    }

    companion object {
        private val declarationRegex = Regex(
            """\b(?:public|private|internal|fileprivate|open|final|indirect|@Observable|@MainActor|\s)*\b(class|struct|enum|protocol|actor)\s+([A-Z][A-Za-z0-9_]*)"""
        )
        private val extensionRegex = Regex("""\bextension\s+([A-Z][A-Za-z0-9_]*)""")
        private val importRegex = Regex("""\bimport\s+(?:[A-Za-z_][A-Za-z0-9_]*\s+)?([A-Z][A-Za-z0-9_]*)""")
        private val typeIdentifierRegex = Regex("""\b[A-Z][A-Za-z0-9_]*\b""")
        private val lineCommentRegex = Regex("""//.*""")
        private val multilineCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val multilineStringRegex = Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL)
        private val stringRegex = Regex(""""(?:\\.|[^"\\])*"""")

        private val swiftKeywords = setOf(
            "Any",
            "AnyObject",
            "Animation",
            "Array",
            "Bool",
            "CGFloat",
            "Character",
            "Data",
            "Date",
            "Decoding",
            "Dictionary",
            "Double",
            "Endpoint",
            "Error",
            "False",
            "Float",
            "ForEach",
            "Identifiable",
            "Int",
            "MainActor",
            "Never",
            "Nil",
            "Optional",
            "RequestEndpoint",
            "RequestResponseEndpoint",
            "ResponseEndpoint",
            "Result",
            "Self",
            "Set",
            "String",
            "Task",
            "Text",
            "True",
            "UInt",
            "URL",
            "URLRequest",
            "URLServer",
            "URLSession",
            "UUID",
            "View",
            "Void"
        )
    }
}
