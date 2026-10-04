package com.learning.docai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * SPEC §7: a Krankenstandsbestaetigung is health data (GDPR Art. 9), and the hosted provider
 * has not been approved for it. The combination is not blocked - the same deployment may be
 * processing synthetic documents - but it must be visible in the log from the first second.
 *
 * <p>Azure OpenAI counts as hosted too: an EU Data Zone deployment keeps processing in the EU,
 * which is a better position for that approval, not a substitute for it.
 */
@Slf4j
@Configuration
@Profile("(anthropic | azure-openai) & prod")
public class HostedModelWarning {

    public HostedModelWarning() {
        log.warn("A hosted model profile is active together with 'prod': documents are sent to "
                + "a hosted model. Real Krankenstandsbestaetigungen are health data (GDPR Art. 9) "
                + "and must not be processed there before data protection has approved it "
                + "(SPEC §7)");
    }
}
