package ru.papasheets.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Заранее заданное значение поля, предлагаемое в автодополнении наравне с историей и раньше неё.
 *
 * Пресет принадлежит полю, а не журналу и не записи: список готовых кодов имеет смысл только внутри
 * того поля, для которого его завели («1-01» — локация, «12 м²» — объём). До v4 таблица называлась
 * `location_presets` и обслуживала одну-единственную «Локацию», из-за чего своё поле прораба
 * получало лишь историю ввода; с появлением экрана полей это ограничение исчезло.
 *
 * `ON DELETE CASCADE` — пресеты часть определения поля и умирают вместе с ним. Это сознательно
 * отличается от `record_values`, где стоит `RESTRICT`: там данные прораба, потерять которые нельзя,
 * а здесь — настройка автодополнения, которую он же и завёл.
 */
@Entity(
    tableName = "field_presets",
    foreignKeys = [
        ForeignKey(
            entity = FieldDefEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["fieldId"])],
)
data class FieldPresetEntity(
    @PrimaryKey val id: String,
    val fieldId: String,
    /** Готовое значение поля — то, что подставится в форму при выборе подсказки. */
    val code: String,
    val orderIndex: Int,
)
