package com.learning.docai.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Directories of the evaluation tool (SPEC §12). Defaults are the layout the spec names, so the
 * `eval` profile needs no configuration of its own; `eval/` is git-ignored.
 *
 * @param input    holds one directory per endpoint: klassifikation, krankenstand,
 *                 zeitbestaetigung, kompetenzprofil
 * @param output   one JSON per input document plus summary.csv, overwritten on each run
 * @param expected optional reference results, matched to an input document by file name
 */
@ConfigurationProperties(prefix = "docai.eval")
public record EvalProperties(
        @DefaultValue("eval/input") Path input,
        @DefaultValue("eval/output") Path output,
        @DefaultValue("eval/expected") Path expected) {
}
