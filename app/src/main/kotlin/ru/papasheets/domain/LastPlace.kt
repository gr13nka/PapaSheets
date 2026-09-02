package ru.papasheets.domain

import android.content.Context
import ru.papasheets.matrixgrid.MatrixViewport

/**
 * Место, на котором прораб бросил работу: какой журнал был открыт и куда в нём доскроллена матрица.
 *
 * Состояние вьюпорта само по себе переживает поворот экрана (`rememberSaveable` в
 * [ru.papasheets.matrixgrid.MatrixState]), но не закрытие приложения, — а закрывают его каждый день:
 * без этого стора прораб назавтра получал список журналов и таблицу с начала.
 *
 * Положение хранится ОТДЕЛЬНО ПО ЖУРНАЛАМ (журнал — это месяц): вернувшись в июльский, попадаешь в
 * июльское место, а не туда, куда доскроллил в августовском. Хранилище —
 * [android.content.SharedPreferences] по образцу [ru.papasheets.domain.export.ExportFolder]: три
 * float'а на журнал не стоят Room-сущности с миграцией.
 *
 * Чего этот стор НЕ помнит — вид (матрица/список), фильтр и порядок дат: они живут в
 * `JournalViewModel` и на запуске сбрасываются. Отсюда правило вызывающей стороны
 * ([JournalQuery.isDefaultMatrixLayout]): сохранять положение только в раскладке по умолчанию,
 * иначе восстановленный вьюпорт указывал бы на совсем другие строки.
 */
class LastPlace(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Журнал, который был открыт последним, или `null`, если журнал ещё ни разу не открывали. */
    fun journalId(): String? = prefs.getString(KEY_JOURNAL, null)

    /**
     * Запоминает открытый журнал. Вызывается на входе в журнал, а не на выходе: даже если процесс
     * убьют без единого шанса сохранить положение, назавтра откроется хотя бы тот же журнал.
     */
    fun rememberJournal(journalId: String) {
        prefs.edit().putString(KEY_JOURNAL, journalId).apply()
    }

    /** Положение матрицы этого журнала, или `null`, если его не сохраняли (или значение испорчено). */
    fun viewportOf(journalId: String): MatrixViewport? =
        ViewportCodec.decode(prefs.getString(viewportKey(journalId), null))

    fun rememberViewport(journalId: String, viewport: MatrixViewport) {
        prefs.edit().putString(viewportKey(journalId), ViewportCodec.encode(viewport)).apply()
    }

    /** Журнала больше нет: снимаем и его положение, и звание последнего, чтобы запуск не вёл в пустоту. */
    fun forget(journalId: String) {
        val editor = prefs.edit().remove(viewportKey(journalId))
        if (journalId() == journalId) editor.remove(KEY_JOURNAL)
        editor.apply()
    }

    private fun viewportKey(journalId: String) = "$KEY_VIEWPORT_PREFIX$journalId"

    private companion object {
        const val PREFS_NAME = "lastPlace"
        const val KEY_JOURNAL = "journalId"
        const val KEY_VIEWPORT_PREFIX = "viewport."
    }
}
