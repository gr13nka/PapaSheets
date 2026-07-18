package ru.papasheets.exportkit.xlsx

/**
 * `.rels`-части пакета. Три статичны (структура файла не меняется от снимка к снимку), одна —
 * `drawing1.xml.rels` — параметризована списком фото: именно она замыкает цепочку
 * workbook → sheet1 → drawing1 → media, которую Excel (в отличие от Sheets) проверяет строго.
 */
internal object RelsXml {
    fun root(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    fun workbook(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""

    fun sheet(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>""" +
            """</Relationships>"""

    fun drawing(anchors: List<PhotoAnchor>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (anchor in anchors) {
            append(
                """<Relationship Id="${anchor.rId}" """ +
                    """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" """ +
                    """Target="../media/${anchor.photoId}.jpg"/>""",
            )
        }
        append("</Relationships>")
    }
}
