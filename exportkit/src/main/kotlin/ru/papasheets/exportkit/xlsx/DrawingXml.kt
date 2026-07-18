package ru.papasheets.exportkit.xlsx

/**
 * `xl/drawings/drawing1.xml`: одна `<xdr:oneCellAnchor>` на фото, якорь = Ф-ячейка записи ([SheetXml]),
 * размер — уже посчитанный [PhotoAnchor.extCx]×[PhotoAnchor.extCy] в EMU ([XlsxWriter] считает его через
 * [Emu] и реальные пиксели фото — здесь только сборка XML).
 */
internal object DrawingXml {
    fun build(anchors: List<PhotoAnchor>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append(
            """<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" """ +
                """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
                """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""",
        )
        anchors.forEachIndexed { index, anchor ->
            val id = index + 1
            append("<xdr:oneCellAnchor>")
            append("<xdr:from><xdr:col>${anchor.col}</xdr:col><xdr:colOff>0</xdr:colOff>")
            append("<xdr:row>${anchor.row}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>")
            append("""<xdr:ext cx="${anchor.extCx}" cy="${anchor.extCy}"/>""")
            append("<xdr:pic>")
            append("""<xdr:nvPicPr><xdr:cNvPr id="$id" name="photo$id.jpg"/><xdr:cNvPicPr/></xdr:nvPicPr>""")
            append("""<xdr:blipFill><a:blip r:embed="${anchor.rId}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>""")
            append("""<xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr>""")
            append("</xdr:pic>")
            append("<xdr:clientData/>")
            append("</xdr:oneCellAnchor>")
        }
        append("</xdr:wsDr>")
    }
}
