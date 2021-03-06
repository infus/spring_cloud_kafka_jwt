package org.nipu.poc.springcloudkafkajwtservice.messaging;

import org.nipu.poc.springcloudkafkajwtservice.SecurityCheckController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.jwt.Jwt;
import org.springframework.security.jwt.JwtHelper;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.util.concurrent.CountDownLatch;

public class Receiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(Receiver.class);

    private final AuthenticationManager authenticationManager;
    private final SecurityCheckController securityCheckController;
    private CountDownLatch latch = new CountDownLatch(1);
    private AuthenticationEventPublisher eventPublisher = new Receiver.NullEventPublisher();

    public Receiver(AuthenticationManager authenticationManager,
                    SecurityCheckController securityCheckController) {
        this.authenticationManager = authenticationManager;
        this.securityCheckController = securityCheckController;
    }

    @KafkaListener(topics = "${spring.kafka.topic.messaging}")
    public void receive(MessageContainer payload) {
        LOGGER.info("received payload='{}'", payload);
        final String tokenValue = (String) payload.getMessage();
        LOGGER.info("Token value {}", tokenValue);
        if (tokenValue != null && !"unauthorized".equals(tokenValue)) {
            //We make an assumption that jwt is verified before pushing message to Kafka
            //And we don't verify it here (for example it may be expired because of service unavailability)
            final Jwt jwt = JwtHelper.decode(tokenValue);
            LOGGER.info("Jwt decoded: {}", jwt);
            PreAuthenticatedAuthenticationToken authentication = new PreAuthenticatedAuthenticationToken(
                    tokenValue,
                    ""
            );
            LOGGER.info("Authentication: {}", authentication);
            checkCurrentContext(authentication);
            if (authentication != null) {
                //TODO: provide details to support usage of OAuth2Authentication programmatically
                if (authentication instanceof AbstractAuthenticationToken) {
                    AbstractAuthenticationToken needsDetails = (AbstractAuthenticationToken) authentication;
                    needsDetails.setDetails(buildDetails(jwt, authentication));
                    LOGGER.info("Authentication token {} \n needs details", needsDetails);
                }
                Authentication authResult = authenticationManager.authenticate(authentication);
                LOGGER.info("Authentication success: {}", authResult);
                eventPublisher.publishAuthenticationSuccess(authResult);
                SecurityContextHolder.getContext().setAuthentication(authResult);
            }
        }
        try {
            LOGGER.info("Try to call unprotected API: {}", securityCheckController.doNotCheck());
        } catch (Exception e) {
            LOGGER.info("Exception caught during request to unprotected API: {}", e);
        }
        try {
            LOGGER.info("Try to call secured user API: {}", securityCheckController.checkUserRole());
        } catch (Exception e) {
            LOGGER.info("Exception caught during request to user protected API: {}", e);
        }
        try {
            LOGGER.info("Try to call secured admin API: {}", securityCheckController.checkAdminRole());
        } catch (Exception e) {
            LOGGER.info("Exception caught during request to admin protected API: {}", e);
        }
        try {
            LOGGER.info("Try to call ant matcher secured API: {}", securityCheckController.checkAuthorization());
        } catch (Exception e) {
            LOGGER.info("Exception caught during request to ant matcher protected API: {}", e);
        }
        try {
            LOGGER.info("Try to call OAuth2Authentication depended API: {}", securityCheckController.checkSecurityExpectations());
        } catch (Exception e) {
            LOGGER.info("Exception caught during request to OAuth2Authentication depended API: {}", e);
        }
        latch.countDown();
    }

    private OAuth2AuthenticationDetails buildDetails(Jwt jwt, PreAuthenticatedAuthenticationToken authentication) {
        JwtKafkaRequestStub jwtKafkaRequestStub = new JwtKafkaRequestStub(jwt);
        return new OAuth2AuthenticationDetails(jwtKafkaRequestStub);
    }

    private void checkCurrentContext(PreAuthenticatedAuthenticationToken authentication) {
        LOGGER.info("Should check and clean current context");
        if (this.isAuthenticated()){
            LOGGER.info("SecurityContext contain authenticated user in current thread");
            SecurityContextHolder.clearContext();
            LOGGER.info("SecurityContext cleaned up");
        }
    }

    private boolean isAuthenticated() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
			return false;
		}
		return true;
	}

    private class NullEventPublisher implements AuthenticationEventPublisher {
        @Override
        public void publishAuthenticationSuccess(Authentication authentication) {
            LOGGER.info("publishAuthenticationSuccess: {}", authentication);
        }

        @Override
        public void publishAuthenticationFailure(AuthenticationException exception, Authentication authentication) {
            LOGGER.info("publishAuthenticationSuccess: {} \n because of: {}", authentication, exception);
        }
    }

}
