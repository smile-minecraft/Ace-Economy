package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.ports.operations.ImportRecord;

import java.util.List;

/**
 * Parser output for one vendor input: normalized records plus per-file or
 * per-line failure descriptions. Failures never silently become zero-balance
 * records; the runner merges them into the overall failed count so a partial
 * import is never reported as fully successful.
 */
public record ImportParseResult(List<ImportRecord> records, List<String> failures) {

    public ImportParseResult {
        records = records == null ? List.of() : List.copyOf(records);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
