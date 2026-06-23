import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class Encryptor {
    public static void main(String[] args) throws Exception {
        String config = "vless://c170ba46-d93b-4e5c-9567-2e616d1d4c4c@snapp.ir:2052?type=ws&encryption=none&path=%2F&host=fandoghfree.moopon.ir&security=none#for-ws-Testmonster";
        String key = "FandoghSecretKey";
        
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        
        byte[] encryptedBytes = cipher.doFinal(config.getBytes("UTF-8"));
        String base64Result = Base64.getEncoder().encodeToString(encryptedBytes);
        
        System.out.println("\n✅ متن دقیق و استاندارد برای جیست شما:\n");
        System.out.println(base64Result);
        System.out.println("\n------------------------------------");
    }
}
