package ru.papasheets.domain

import ru.papasheets.data.db.entity.ContractorEntity

/**
 * Список подрядчиков для дропдауна формы записи: активные — всегда, плюс подрядчик РЕДАКТИРУЕМОЙ
 * записи, если он архивный. Без этого добавления открытие на редактирование записи архивного
 * подрядчика показало бы пустое поле вместо его имени — дропдаун просто не содержал бы такого id.
 * [currentContractorId] — null в режиме создания, там архивный подрядчик предлагаться не должен.
 */
fun buildContractorOptions(all: List<ContractorEntity>, currentContractorId: String?): List<ContractorEntity> {
    val active = all.filter { !it.isArchived }
    val current = currentContractorId?.let { id -> all.find { it.id == id } }
    return if (current != null && current.isArchived) active + current else active
}

/**
 * Имя подрядчика для дропдауна с пометкой архивных — иначе неясно, почему он единственный вне списка
 * активных. [archivedTemplate] — шаблон пометки из ресурсов (`record_contractor_archived`, `%1$s` — имя):
 * где встанет имя, решает перевод, а не код.
 */
fun contractorDisplayName(contractor: ContractorEntity, archivedTemplate: String): String =
    if (contractor.isArchived) archivedTemplate.format(contractor.name) else contractor.name
