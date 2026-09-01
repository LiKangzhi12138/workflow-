package com.workflow.utils;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Slf4j
public class AesEncryptionUtil {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int KEY_SIZE = 256;
    private static final int IV_SIZE = 128;

    private AesEncryptionUtil() {
    }

    public static String generateKeyBase64() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(KEY_SIZE);
            SecretKey key = keyGen.generateKey();
            return Base64.getEncoder().encodeToString(key.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to generate AES key", e);
            throw new RuntimeException("Failed to generate AES key", e);
        }
    }

    public static String generateIvBase64() {
        try {
            SecureRandom random = new SecureRandom();
            byte[] iv = new byte[IV_SIZE / 8];
            random.nextBytes(iv);
            return Base64.getEncoder().encodeToString(iv);
        } catch (Exception e) {
            log.error("Failed to generate AES IV", e);
            throw new RuntimeException("Failed to generate AES IV", e);
        }
    }

    public static byte[] encrypt(byte[] plainBytes, String keyBase64, String ivBase64) {
        try {
            byte[] decodedKey = Base64.getDecoder().decode(keyBase64);
            byte[] decodedIv = Base64.getDecoder().decode(ivBase64);
            SecretKey secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
            IvParameterSpec iv = new IvParameterSpec(decodedIv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);
            return cipher.doFinal(plainBytes);
        } catch (Exception e) {
            log.error("Failed to encrypt bytes with AES-CBC", e);
            throw new RuntimeException("Failed to encrypt bytes with AES-CBC", e);
        }
    }

    public static byte[] decrypt(byte[] encryptedBytes, String keyBase64, String ivBase64) {
        try {
            byte[] decodedKey = Base64.getDecoder().decode(keyBase64);
            byte[] decodedIv = Base64.getDecoder().decode(ivBase64);
            SecretKey secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
            IvParameterSpec iv = new IvParameterSpec(decodedIv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, iv);
            return cipher.doFinal(encryptedBytes);
        } catch (Exception e) {
            log.error("Failed to decrypt bytes with AES-CBC", e);
            throw new RuntimeException("Failed to decrypt bytes with AES-CBC", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to calculate SHA-256", e);
            throw new RuntimeException("Failed to calculate SHA-256", e);
        }
    }
}
