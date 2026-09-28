package com.learning.docai.validation;

/**
 * What ibosNG already knows about the Teilnehmer, passed as multipart fields so it never
 * reaches a URL or an access log (SPEC §3). Every part is optional: a hint that is absent is
 * simply not compared.
 */
public record Teilnehmerhinweis(String vorname, String familienname, String svnr) {

    public static Teilnehmerhinweis ohne() {
        return new Teilnehmerhinweis(null, null, null);
    }
}
