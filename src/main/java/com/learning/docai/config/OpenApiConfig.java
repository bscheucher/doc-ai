package com.learning.docai.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * The OpenAPI document of SPEC §10, served at /v3/api-docs with the UI at /swagger-ui.html
 * (the UI is switched off in `prod` from application.yml).
 *
 * <p>The bearer scheme is declared once and required for every operation: all of /api/** needs
 * the app role of SPEC §7, and a schema that does not say so would send a caller into a 401.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "entraBearer";

    @Bean
    OpenAPI docAiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("doc-ai")
                        .version("v1")
                        .description("""
                                Klassifiziert und extrahiert Daten aus eingescannten Dokumenten \
                                (Krankenstandsbestaetigung, Zeitbestaetigung, AMS-Kompetenzprofil). \
                                Ersetzt die natif.ai-Workflows des ibosNG-Backends.

                                Jeder Aufruf ist zustandslos und synchron: das Dokument wird im \
                                Speicher verarbeitet und nie gespeichert. Fehlende Felder sind \
                                kein Fehler - die Antwort enthaelt dann `probleme` und \
                                `manuellePruefung: true`."""))
                .servers(List.of(new Server().url("/").description("Diese Instanz")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("""
                                Access token aus dem Client-Credentials-Flow von Azure Entra ID, \
                                mit der App-Rolle DocAi.Process.""")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
