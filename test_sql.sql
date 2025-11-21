SELECT res ->> 'error' AS error, res ->> 'success' AS success, res ->> 'requestBody' AS request_body, res ->> 'responseBody' AS response_body, (res ->> 'executionTimeMs'):: int AS execution_time_ms, (res ->> 'statusCode'):: int AS status_code, (res ->> 'rowIndex'):: int AS row_index, vr.vrow -> 'data' -> 'Test Case ID' ->> 'value' AS test_case_id, vr.vrow -> 'data' -> 'Description' ->> 'value' AS description
FROM th_kt_bulk_executions t
    JOIN job_executions j
ON j.execution_id = t.id::text
    CROSS JOIN LATERAL jsonb_array_elements(t.results) AS res
    CROSS JOIN LATERAL (
    SELECT v.vrow
    FROM jsonb_array_elements(j.job_payload -> 'excelData' -> 'validRows') AS v(vrow)
    WHERE (v.vrow ->> 'originalRowIndex'):: int = (res ->> 'rowIndex'):: int
    LIMIT 1
    ) AS vr
WHERE t.id = '528ad620-b09c-4af2-a75a-69ef1d3a6f9c'
ORDER BY (res ->> 'rowIndex'):: int;


ALTER TABLE public.job_executions
    ADD COLUMN IF NOT EXISTS bulk_execution_id uuid;

CREATE TABLE IF NOT EXISTS public.bulk_execution_results
(
    bulk_execution_id
    uuid
    NOT
    NULL,
    row_index
    integer
    NOT
    NULL,
    test_case_id
    text,
    description
    text,
    request_body
    jsonb,
    response_body
    jsonb,
    status_code
    integer,
    success
    boolean,
    error
    text,
    execution_time_ms
    integer,
    created_at
    timestamptz
    DEFAULT
    CURRENT_TIMESTAMP,
    PRIMARY
    KEY
(
    bulk_execution_id,
    row_index
),
    CONSTRAINT fk_bulk_exec
    FOREIGN KEY
(
    bulk_execution_id
) REFERENCES public.th_kt_bulk_executions
(
    id
) ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS idx_bulk_results_exec ON public.bulk_execution_results(bulk_execution_id);
CREATE INDEX IF NOT EXISTS idx_bulk_results_exec_success ON public.bulk_execution_results(bulk_execution_id, success);
-- If you ever search by testCaseId:
CREATE INDEX IF NOT EXISTS idx_bulk_results_exec_tcid ON public.bulk_execution_results(bulk_execution_id, test_case_id);

-- 3) (Optional) Keep row “header” separate; useful if you’ll enrich with more metadata later
CREATE TABLE IF NOT EXISTS public.bulk_execution_rows
(
    bulk_execution_id
    uuid
    NOT
    NULL,
    row_index
    integer
    NOT
    NULL,
    test_case_id
    text,
    description
    text,
    PRIMARY
    KEY
(
    bulk_execution_id,
    row_index
),
    CONSTRAINT fk_rows_bulk
    FOREIGN KEY
(
    bulk_execution_id
) REFERENCES public.th_kt_bulk_executions
(
    id
) ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS idx_bulk_rows_exec ON public.bulk_execution_rows(bulk_execution_id);