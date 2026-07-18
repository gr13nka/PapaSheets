package ru.papasheets.exportkit.xlsx

/** Индексы `cellXfs` из [StylesXml.build] — единственное, что остальным частям xlsx нужно о стилях знать. */
internal const val STYLE_DEFAULT = 0
internal const val STYLE_HEADER_BOLD = 1
internal const val STYLE_HEADER_LABEL = 2
internal const val STYLE_WRAP = 3

/**
 * Минимальный `xl/styles.xml`: жирная шапка (contractor-имена и «ДАТА»), жирные центрированные
 * подписи Ф/Л/ВИД РАБОТ, перенос текста для колонки «ВИД РАБОТ». Рамки и заливки не нужны (см.
 * docs/spec.md, M6) — эталонный журнал их тоже не требует для читаемости.
 */
internal object StylesXml {
    fun build(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2">""" +
            """<font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><name val="Calibri"/></font>""" +
            """</fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border/></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="4">""" +
            // STYLE_DEFAULT — обычный текст (дата, локация, пустые ячейки под фото).
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            // STYLE_HEADER_BOLD — «ДАТА» и имена подрядчиков в строке 1.
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            // STYLE_HEADER_LABEL — «Ф»/«Л»/«ВИД РАБОТ» в строке 2, по центру.
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center"/></xf>""" +
            // STYLE_WRAP — текст «вида работ» в данных: перенос по словам, выравнивание по верху ячейки.
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>""" +
            """</cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""
}
