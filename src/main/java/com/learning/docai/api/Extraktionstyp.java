package com.learning.docai.api;

/**
 * What an extraction endpoint produced, reported as `dokumenttyp` in the envelope
 * (SPEC §3.1). Fixed per endpoint - unlike endpoint 1, which decides the type, these
 * endpoints are told which document they are looking at.
 *
 * <p>Deliberately not the same enum as {@code klassifikation.Dokumenttyp}: that one is the
 * model's answer and can be UNBEKANNT, this one is our own statement about a response.
 */
public enum Extraktionstyp {

    KRANKENSTAND,
    ZEITBESTAETIGUNG,
    KOMPETENZPROFIL
}
