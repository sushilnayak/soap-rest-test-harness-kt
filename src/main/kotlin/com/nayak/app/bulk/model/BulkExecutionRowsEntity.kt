package com.nayak.app.bulk.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.util.*

@Table("bulk_execution_rows")
data class BulkExecutionRowsEntity(

    @Id
    @Column("row_index")
    val rowIndex: Int,

    @Column("bulk_execution_id")
    val bulkExecutionId: UUID,

    @Column("test_case_id")
    val testCaseId: String? = null,

    @Column("description")
    val description: String? = null
)