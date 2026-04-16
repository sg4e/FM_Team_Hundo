package moe.maika.fmteamhundo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;

@Service
public class ApiKeyService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int KEY_BYTE_LENGTH = 32;

    private final UserRepository userRepository;
    private final ConcurrentMap<String, Optional<User>> apiKeyCache = new ConcurrentHashMap<>();

    @Autowired
    public ApiKeyService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String generateNewApiKey(User user) {
        String rawKey = generateRandomHex(KEY_BYTE_LENGTH);
        String hash = sha256Hex(rawKey);
        user.setApiKeyHash(hash);
        userRepository.save(user);
        apiKeyCache.clear();
        return rawKey;
    }

    public Optional<User> getUserForApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        String hash = sha256Hex(apiKey.strip());
        return apiKeyCache.computeIfAbsent(hash, userRepository::findByApiKeyHash);
    }

    private static String generateRandomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashedBytes.length * 2);
            for (byte b : hashedBytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
