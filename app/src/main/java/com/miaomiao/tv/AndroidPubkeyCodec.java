package com.miaomiao.tv;

import android.util.Base64;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;

/**
 * Android ADB 公钥编码解码工具
 *
 * 实现 AndroidPubkey 二进制格式（用于 ~/.android/adb_keys 或 ADB 协议中的公钥传输）。
 *
 * 格式：
 *   base64(modulus || " " || user_info || "\n")
 *
 * 其中 modulus 是 little-endian 64字节 RSA 模数，指数固定为 65537。
 * 与标准 ASN.1 DER / PKCS#1 格式不同，这是 Android 自己定义的格式。
 *
 * 不依赖任何 android.sun.security.* 内部 API，纯标准 Java 实现。
 */
public class AndroidPubkeyCodec {

    private static final BigInteger DEFAULT_EXPONENT = BigInteger.valueOf(65537);

    /**
     * 将 RSAPublicKey 编码为 AndroidPubkey 二进制格式
     *
     * AndroidPubkey 格式 = base64(little-endian-modulus || " " || user_info || "\n")
     *
     * @param pubKey RSA 公钥
     * @return AndroidPubkey 格式的字节数组（未 base64）
     */
    public static byte[] encode(RSAPublicKey pubKey) {
        // 1. little-endian 模数（固定 256 字节，RSA-2048）
        byte[] modLE = toLittleEndian(pubKey.getModulus(), 256);

        // 2. base64(模数 || " " || user_info || "\n")
        String userInfo = " MiaoMiaoTV\n";
        byte[] payload = new byte[modLE.length + userInfo.length()];
        System.arraycopy(modLE, 0, payload, 0, modLE.length);
        byte[] userBytes = userInfo.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(userBytes, 0, payload, modLE.length, userBytes.length);

        return payload;
    }

    /**
     * 将 AndroidPubkey 字节数组解码为 RSAPublicKey
     *
     * @param androidPubkeyBytes AndroidPubkey 格式（未 base64）的字节数组
     * @return 标准 RSAPublicKey
     */
    public static RSAPublicKey decode(byte[] androidPubkeyBytes) {
        // AndroidPubkey 格式 = base64(little-endian-modulus || " " || user_info || "\n")
        // 解析出 little-endian 模数
        byte[] modLE = new byte[256];  // RSA-2048 固定 256 字节
        int minLen = Math.min(modLE.length, androidPubkeyBytes.length);
        System.arraycopy(androidPubkeyBytes, 0, modLE, 0, minLen);

        // little-endian → big-endian
        byte[] modBE = reverse(modLE);

        // 构造 RSAPublicKey（无构造器，用反射）
        try {
            java.lang.reflect.Constructor<RSAPublicKey> ctor =
                    RSAPublicKey.class.getDeclaredConstructor(BigInteger.class, BigInteger.class);
            ctor.setAccessible(true);
            return ctor.newInstance(new BigInteger(1, modBE), DEFAULT_EXPONENT);
        } catch (Exception e) {
            throw new RuntimeException("无法构造 RSAPublicKey", e);
        }
    }

    /**
     * 将 BigInteger 转为固定长度 little-endian 字节数组
     * 
     * BigInteger.toByteArray() 返回有符号补码表示：
     * - 正数且 MSB=0（byte[0] < 0x80）：直接返回，无符号位 → 长度 = (bitLength+7)/8
     * - 正数且 MSB=1（byte[0] >= 0x80）：前补 0x00 符号位 → 长度 = (bitLength+7)/8 + 1
     * 
     * 例如 RSA-2048（bitLength=2048，byte[0] >= 0x80）：
     *   toByteArray() → 257 字节（符号位 + 256 字节模数）
     *   去掉符号位 → 正好 256 字节 ✅
     */
    private static byte[] toLittleEndian(BigInteger value, int length) {
        byte[] be = value.toByteArray();
        
        // 情况1：toByteArray() 加了 0x00 符号位（MSB=1，需要去掉）
        // RSA-2048 bitLength=2048，MSB=1 时 toByteArray 返回 257 字节
        if (be.length == length + 1 && be[0] == 0x00) {
            byte[] trimmed = new byte[length];
            System.arraycopy(be, 1, trimmed, 0, length);
            be = trimmed;
        }
        // 情况2：toByteArray() 直接返回，无符号位（MSB=0 或 bitLength%8==0 不需符号位）
        // 此时 be.length == length，无需处理
        // 情况3：be.length < length，需要在前面补零（高位补零）
        
        byte[] result = new byte[length];
        int offset = length - be.length;
        System.arraycopy(be, 0, result, offset, be.length);
        return result;
    }

    private static byte[] reverse(byte[] arr) {
        byte[] r = new byte[arr.length];
        for (int i = 0; i < arr.length; i++) {
            r[i] = arr[arr.length - 1 - i];
        }
        return r;
    }
}
