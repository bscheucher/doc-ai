package com.learning.docai.klassifikation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.converter.BeanOutputConverter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SPEC §3.2: the classification must survive an answer that is not one of the constants. The
 * model is asked for the vocabulary, but a wrong spelling is not worth a failed request.
 */
class DokumenttypTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = { "KRANKENSTANDSBESTAETIGUNG", "krankenstandsbestaetigung",
            "KRANKENSTANDSBESTÄTIGUNG", "Krankenstandsbestätigung", " Krankenstandsbestaetigung " })
    void acceptsTheSpellingsAModelActuallyAnswers(String answer) throws Exception {
        assertThat(typFor(answer)).isEqualTo(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG);
    }

    @ParameterizedTest
    @ValueSource(strings = { "Krankenstand", "SONSTIGES", "" })
    void foldsAnAnswerOutsideTheVocabularyToUnbekannt(String answer) throws Exception {
        // Guessing which class was meant is worse than handing the document to a person, and
        // failing the request would cost a second model call and end in a 502.
        assertThat(typFor(answer)).isEqualTo(Dokumenttyp.UNBEKANNT);
    }

    @Test
    void leavesAnAbsentTypeToTheService() throws Exception {
        KlassifikationErgebnis ergebnis = objectMapper.readValue(
                "{\"begruendung\":\"nichts erkennbar\"}", KlassifikationErgebnis.class);

        assertThat(ergebnis.typ()).isNull();
    }

    @Test
    void stillOffersEveryConstantToTheModelInTheSchema() {
        // Tolerating odd spellings must not cost the enum in the structured-output schema:
        // that schema is what keeps the model inside the vocabulary in the first place.
        String schema = new BeanOutputConverter<>(KlassifikationErgebnis.class).getJsonSchema();

        assertThat(schema)
                .contains("KRANKENSTANDSBESTAETIGUNG")
                .contains("ZEITBESTAETIGUNG")
                .contains("UNBEKANNT");
    }

    private Dokumenttyp typFor(String answer) throws Exception {
        String json = objectMapper.writeValueAsString(
                Map.of("typ", answer, "begruendung", "egal"));
        return objectMapper.readValue(json, KlassifikationErgebnis.class).typ();
    }
}
