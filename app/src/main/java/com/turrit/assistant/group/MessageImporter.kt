package com.turrit.assistant.group

/** XLSX message parsing and validation are implemented in phase 4. */
interface MessageImporter {
    fun supportedDescription(): String = "群消息记录（.xlsx）"
}
