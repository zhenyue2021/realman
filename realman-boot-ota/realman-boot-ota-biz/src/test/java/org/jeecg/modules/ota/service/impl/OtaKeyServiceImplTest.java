package org.jeecg.modules.ota.service.impl;

import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.ota.mapper.OtaKeyMapper;
import org.jeecg.modules.ota.service.OtaAuditService;
import org.jeecg.modules.ota.vo.KeyDTO;
import org.jeecg.modules.ota.vo.KeyUploadRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ed25519 公钥上传的格式/算法校验是签名验证链路的第一道关卡，覆盖"只接受标准
 * X.509 SubjectPublicKeyInfo DER 编码的 Ed25519 公钥"这条明确的安全约束
 * （见 {@link OtaKeyServiceImpl} 类注释）：合法 Ed25519 通过、非 Base64 拒绝、
 * 其它算法（如 RSA）拒绝，不允许静默接受错误算法的公钥。
 */
@ExtendWith(MockitoExtension.class)
class OtaKeyServiceImplTest {

    @Mock
    private OtaKeyMapper keyMapper;

    @Mock
    private OtaAuditService auditService;

    private OtaKeyServiceImpl keyService;

    @BeforeEach
    void setUp() {
        keyService = new OtaKeyServiceImpl(keyMapper, auditService);
    }

    private String toPem(byte[] derEncodedPublicKey) {
        String base64 = Base64.getEncoder().encodeToString(derEncodedPublicKey);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    @Test
    void upload_shouldAcceptValidEd25519Pem() throws NoSuchAlgorithmException {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyUploadRequest request = new KeyUploadRequest();
        request.setPublicKeyPem(toPem(keyPair.getPublic().getEncoded()));
        request.setKeyAlias("test-key");

        KeyDTO dto = keyService.upload(request, "tester");

        assertThat(dto.getKeyFingerprint()).hasSize(32);
        assertThat(dto.getStatus()).isEqualTo("pending_activation");
        assertThat(dto.getKeyAlias()).isEqualTo("test-key");
    }

    @Test
    void upload_shouldRejectNonBase64PemContent() {
        KeyUploadRequest request = new KeyUploadRequest();
        request.setPublicKeyPem("-----BEGIN PUBLIC KEY-----\nnot valid base64 !!!\n-----END PUBLIC KEY-----");

        assertThatThrownBy(() -> keyService.upload(request, "tester"))
                .isInstanceOf(JeecgBootBizTipException.class)
                .hasMessageContaining("ERR_KEY_FORMAT_INVALID");
    }

    @Test
    void upload_shouldRejectNonEd25519Algorithm() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        KeyUploadRequest request = new KeyUploadRequest();
        request.setPublicKeyPem(toPem(keyPair.getPublic().getEncoded()));

        assertThatThrownBy(() -> keyService.upload(request, "tester"))
                .isInstanceOf(JeecgBootBizTipException.class)
                .hasMessageContaining("ERR_KEY_FORMAT_INVALID");
    }
}
