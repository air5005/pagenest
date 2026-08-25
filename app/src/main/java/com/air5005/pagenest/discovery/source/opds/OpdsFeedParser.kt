package com.air5005.pagenest.discovery.source.opds

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

data class ParsedOpdsFeed(
    val nextUrl: String?,
    val entries: List<ParsedOpdsEntry>,
)

data class ParsedOpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    val summary: String?,
    val languages: List<String>,
    val subjects: List<String>,
    val coverUrl: String?,
    val updated: String?,
    val acquisitions: List<ParsedOpdsLink>,
)

data class ParsedOpdsLink(
    val href: String,
    val type: String,
)

class OpdsParseException : RuntimeException("Invalid OPDS document")

class OpdsFeedParser {
    fun parse(xml: String): ParsedOpdsFeed = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(exception: SAXParseException) = throw exception
                override fun fatalError(exception: SAXParseException) = throw exception
            })
        }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        ParsedOpdsFeed(
            nextUrl = root.childElements("link")
                .firstOrNull { it.attribute("rel") == "next" }
                ?.attribute("href")
                ?.takeIf(String::isNotBlank),
            entries = root.childElements("entry").mapNotNull(::parseEntry),
        )
    } catch (_: Exception) {
        throw OpdsParseException()
    }

    private fun parseEntry(element: Element): ParsedOpdsEntry? {
        val id = element.firstText("id") ?: return null
        val title = element.firstText("title") ?: return null
        val links = element.childElements("link")
        return ParsedOpdsEntry(
            id = id,
            title = title,
            authors = element.childElements("author").mapNotNull { it.firstText("name") },
            summary = element.firstText("summary") ?: element.firstText("content"),
            languages = element.childElements("language").mapNotNull { it.trimmedText() },
            subjects = element.childElements("category")
                .mapNotNull { it.attribute("term").takeIf(String::isNotBlank) },
            coverUrl = links.firstOrNull {
                it.attribute("rel") in COVER_RELATIONS
            }?.attribute("href")?.takeIf(String::isNotBlank),
            updated = element.firstText("updated"),
            acquisitions = links.mapNotNull { link ->
                val relation = link.attribute("rel")
                val href = link.attribute("href")
                val type = link.attribute("type")
                if (!relation.contains(ACQUISITION_RELATION) || href.isBlank() || type.isBlank()) {
                    null
                } else {
                    ParsedOpdsLink(href, type)
                }
            },
        )
    }

    private fun Element.firstText(localName: String): String? = childElements(localName)
        .firstNotNullOfOrNull { it.trimmedText() }

    private fun Element.trimmedText(): String? = textContent?.trim()?.takeIf(String::isNotBlank)

    private fun Element.childElements(localName: String): List<Element> = buildList {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val child = nodes.item(index) as? Element ?: continue
            if (child.localName == localName || child.nodeName == localName) add(child)
        }
    }

    private fun Element.attribute(name: String): String = getAttribute(name).trim()

    private companion object {
        const val ACQUISITION_RELATION = "http://opds-spec.org/acquisition"
        val COVER_RELATIONS = setOf(
            "http://opds-spec.org/image",
            "http://opds-spec.org/image/thumbnail",
        )
    }
}
