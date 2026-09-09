package com.finflow.studio.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class SessionTokenService {
    private static final String ISSUER = "finflow-studio";
    private final Algorithm algorithm;
    private final long sessionHours;

    public SessionTokenService(@Value("${finflow.auth.jwt-secret}") String secret,
                               @Value("${finflow.auth.session-hours:12}") long sessionHours) {
        this.algorithm = Algorithm.HMAC256(secret);
        this.sessionHours = sessionHours;
    }

    public String issue(String username) {
        var now = Instant.now();
        return JWT.create().withIssuer(ISSUER).withSubject(username)
                .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plus(sessionHours, ChronoUnit.HOURS)))
                .sign(algorithm);
    }

    public String verify(String token) {
        return JWT.require(algorithm).withIssuer(ISSUER).build().verify(token).getSubject();
    }
}
