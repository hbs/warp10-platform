//
//   Copyright 2023  SenX S.A.S.
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.script.functions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGKey;
import org.bouncycastle.bcpg.ECDHPublicBCPGKey;
import org.bouncycastle.bcpg.ECDSAPublicBCPGKey;
import org.bouncycastle.bcpg.ECSecretBCPGKey;
import org.bouncycastle.bcpg.EdSecretBCPGKey;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.bcpg.RSAPublicBCPGKey;
import org.bouncycastle.bcpg.RSASecretBCPGKey;
import org.bouncycastle.bcpg.SecretKeyPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.bouncycastle.math.ec.FixedPointUtil;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.PGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;

import org.bouncycastle.openpgp.PGPPrivateKey;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

public class PGPGEN extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  private static final String PARAM_CURVE = "curve";
  private static final String PARAM_D = "d";
  private static final String PARAM_ID = "id";
  private static final String PARAM_PASSPHRASE = "passphrase";
  private static final String PARAM_SIGNATURE = "signature";
  private static final String PARAM_ENCRYPTION = "encryption";
  private static final String PARAM_SIZE = "size";
  private static final String PARAM_PUBLIC = "public";
  private static final String PARAM_PRIVATE = "private";

  public PGPGEN(String name) {
    super(name);
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {

    Object top = stack.pop();

    if (!(top instanceof Map)) {
      throw new WarpScriptException(getName() + " expects a parameter MAP.");
    }

    Map<Object,Object> params = (Map<Object,Object>) top;

    String passphrase = "";

    if (null != params.get(PARAM_PASSPHRASE) && !(params.get(PARAM_PASSPHRASE) instanceof String)) {
      throw new WarpScriptException(getName() + " invalid passphrase, expected a STRING.");
    } else if (params.get(PARAM_PASSPHRASE) instanceof String) {
      passphrase = (String) params.get(PARAM_PASSPHRASE);
    }

    int keysize = 3072;

    if (null != params.get(PARAM_SIZE)) {
      if (params.get(PARAM_SIZE) instanceof Long) {
        keysize = ((Long) params.get(PARAM_SIZE)).intValue();
      } else {
        throw new WarpScriptException(getName() + " invalid master key size.");
      }
    }

    int certainty = 12;
    int s2kcount = 0x60;
    long exponent = 0x10001L;

    String id = String.valueOf(params.getOrDefault(PARAM_ID,UUID.randomUUID().toString()));

    try {
      RSAKeyPairGenerator  kpg = new RSAKeyPairGenerator();
      kpg.init(new RSAKeyGenerationParameters(BigInteger.valueOf(exponent), new SecureRandom(), keysize, certainty));
      // First create the master (signing) key with the generator.
      PGPKeyPair rsakp_sign = new BcPGPKeyPair(PGPPublicKey.RSA_SIGN, kpg.generateKeyPair(), new Date());
//      // Then an encryption subkey.
//      PGPKeyPair rsakp_enc = new BcPGPKeyPair(PGPPublicKey.RSA_ENCRYPT, kpg.generateKeyPair(), new Date());

      // Add a self-signature on the id
      PGPSignatureSubpacketGenerator signhashgen = new PGPSignatureSubpacketGenerator();
      // Add signed metadata on the signature.

      // 1) Declare its purpose
      signhashgen.setKeyFlags(false, KeyFlags.SIGN_DATA|KeyFlags.CERTIFY_OTHER);

      // 2) Set preferences for secondary crypto algorithms to use when sending messages to this key.
      signhashgen.setPreferredSymmetricAlgorithms(false, new int[] {
        SymmetricKeyAlgorithmTags.AES_256,
        SymmetricKeyAlgorithmTags.AES_192,
        SymmetricKeyAlgorithmTags.AES_128
      });
      signhashgen.setPreferredHashAlgorithms(false, new int[] {
        HashAlgorithmTags.SHA256,
        HashAlgorithmTags.SHA1,
        HashAlgorithmTags.SHA384,
        HashAlgorithmTags.SHA512,
        HashAlgorithmTags.SHA224,
      });

      // 3) Request senders add additional checksums to the message (useful when verifying unsigned messages.)
      signhashgen.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION);

      // Objects used to encrypt the secret key.
      PGPDigestCalculator sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
      PGPDigestCalculator sha256Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256);

      // bcpg 1.48 exposes this API that includes s2kcount. Earlier versions use a default of 0x60.
      PBESecretKeyEncryptor pske = (new BcPBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha256Calc, s2kcount)).build(passphrase.toCharArray());

      // Finally, create the keyring itself. The constructor takes parameters that allow it to generate the self signature.
      PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, rsakp_sign, id, sha1Calc, signhashgen.generate(), null, new BcPGPContentSignerBuilder(rsakp_sign.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1), pske);

      PublicKeyPacket pkp = null;
      BCPGKey priv = null;

      boolean forEnc = (null == params.get(PARAM_ENCRYPTION) && null == params.get(PARAM_SIGNATURE)) || Boolean.TRUE.equals(params.get(PARAM_ENCRYPTION));
      boolean forSig = (null == params.get(PARAM_ENCRYPTION) && null == params.get(PARAM_SIGNATURE)) || Boolean.TRUE.equals(params.get(PARAM_SIGNATURE));

      if (params.get(PARAM_CURVE) instanceof String) {
        String curve = (String) params.get(PARAM_CURVE);

        if (!(params.get(PARAM_D) instanceof String)) {
          throw new WarpScriptException(getName() + " missing ECC private key '" + PARAM_D + "'.");
        }

        String dstr = (String) params.get(PARAM_D);

        BigInteger d = null;

        if (dstr.startsWith("0x")) {
          d = new BigInteger(dstr.substring(2), 16);
        } else {
          d = new BigInteger(dstr);
        }

        // Valid curves are Curve25519, Ed25519, NIST P-256, NIST P-384, NIST P-521
        ECNamedCurveParameterSpec spec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec(curve);

        if (null == spec) {
          throw new WarpScriptException(getName() + " unknown curve.");
        }

        ECPoint q = null;

        ECCurve c = spec.getG().getCurve();
        int size = FixedPointUtil.getCombSize(c);

        if (d.bitLength() > size) {
          q = spec.getG().multiply(d);
        } else {
          q = new FixedPointCombMultiplier().multiply(spec.getG(), d);
        }

        if (forSig && CryptlibObjectIdentifiers.curvey25519 == ECNamedCurveTable.getOID(curve)) {
          priv = new EdSecretBCPGKey(d);
        } else {
          priv = new ECSecretBCPGKey(d);
        }

        if (forSig) {
          if (forEnc) {
            throw new WarpScriptException(getName() + " ECC key can either sign or encrypt, not both at the same time, please specify one of '" + PARAM_ENCRYPTION +"' or '" + PARAM_SIGNATURE + "'.");
          }
          ECDSAPublicBCPGKey pub = new ECDSAPublicBCPGKey(ECNamedCurveTable.getOID(curve), q);
          if (pub.getCurveOID() == CryptlibObjectIdentifiers.curvey25519) {
            pkp = new PublicKeyPacket(PublicKeyAlgorithmTags.EDDSA, new Date(), pub);
          } else {
            pkp = new PublicKeyPacket(PublicKeyAlgorithmTags.ECDSA, new Date(), pub);
          }
        } else {
          ECDHPublicBCPGKey pub = new ECDHPublicBCPGKey(ECNamedCurveTable.getOID(curve), q, HashAlgorithmTags.SHA512, SymmetricKeyAlgorithmTags.AES_256);
          pkp = new PublicKeyPacket(PublicKeyAlgorithmTags.ECDH, new Date(), pub);
        }
      } else if (params.get(PARAM_PRIVATE) instanceof RSAPrivateKey && params.get(PARAM_PUBLIC) instanceof RSAPublicKey) {
        RSAPublicKey rsapub = (RSAPublicKey) params.get(PARAM_PUBLIC);
        RSAPrivateKey rsapriv = (RSAPrivateKey) params.get(PARAM_PRIVATE);

        //
        // Extract encoded key
        //

        if (!"DER".equals(rsapriv.getFormat()) || null == rsapriv.getEncoded()) {
          throw new WarpScriptException(getName() + " missing encoded key.");
        }

        ASN1Sequence seq;

        try {
          seq = ASN1Sequence.getInstance(DERSequence.fromByteArray(rsapriv.getEncoded()));
        } catch (IOException ioe) {
          throw new WarpScriptException(getName() + " error deserializing key.", ioe);
        }

        priv = new RSASecretBCPGKey(rsapriv.getPrivateExponent(), ASN1Integer.getInstance(seq.getObjectAt(4)).getValue(), ASN1Integer.getInstance(seq.getObjectAt(5)).getValue());
        BCPGKey pub = new RSAPublicBCPGKey(rsapub.getModulus(), rsapub.getPublicExponent());

        int purpose = PublicKeyAlgorithmTags.RSA_GENERAL;

        if (!forEnc || !forSig) {
          if (forSig) {
            purpose = PublicKeyAlgorithmTags.RSA_SIGN;
          } else if (forEnc) {
            purpose = PublicKeyAlgorithmTags.RSA_SIGN;
          }
        }

        pkp = new PublicKeyPacket(purpose, new Date(), pub);
      }

      PGPPublicKey pubKey = new PGPPublicKey(pkp, new BcKeyFingerprintCalculator());
      PGPPrivateKey ppk = new PGPPrivateKey(0, pkp, priv);
      PGPKeyPair kp = new PGPKeyPair(pubKey, ppk);

      // Create a signature on the encryption subkey.
      PGPSignatureSubpacketGenerator enchashgen = new PGPSignatureSubpacketGenerator();

      // Add metadata to declare its purpose
      int flags = 0;

      if (forSig) {
        flags |= KeyFlags.SIGN_DATA;
      }

      if (forEnc) {
        flags |= KeyFlags.ENCRYPT_COMMS|KeyFlags.ENCRYPT_STORAGE;
      }
      enchashgen.setKeyFlags(false, flags);
      keyRingGen.addSubKey(kp, enchashgen.generate(), null);

      // INFO(hbs): in the case of a signing key, we do not emit a cross-certification (the signing of the master key using the subkey), so
      //            verifying signatures made by the subkey will lead GPG to issue a 'signing subkey 0x... is not cross-certified' warning
      PGPSecretKeyRing skr =  keyRingGen.generateSecretKeyRing();

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ArmoredOutputStream armored = new ArmoredOutputStream(out);
      skr.encode(armored);
      armored.close();
      byte[] data = out.toByteArray();
      stack.push(new String(data, 0, data.length, StandardCharsets.US_ASCII));

      return stack;
    } catch (PGPException|IOException e) {
      throw new WarpScriptException(getName() + " error while generating PGP key ring.");
    }
//    PublicKeyPacket pkp = null;
//    BCPGKey priv = null;
//    boolean master = false;
//    String uid = String.valueOf(params.getOrDefault(PARAM_ID, UUID.randomUUID().toString()));
//
//    if (params.get(PARAM_CURVE) instanceof String) {
//      String curve = (String) params.get(PARAM_CURVE);
//
//      if (!(params.get(PARAM_D) instanceof String)) {
//        throw new WarpScriptException(getName() + " missing ECC private key '" + PARAM_D + "'.");
//      }
//
//      String dstr = (String) params.get(PARAM_D);
//
//      BigInteger d = null;
//
//      if (dstr.startsWith("0x")) {
//        d = new BigInteger(dstr.substring(2), 16);
//      } else {
//        d = new BigInteger(dstr);
//      }
//
//      ECNamedCurveParameterSpec spec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec(curve);
//      ECPoint q = new FixedPointCombMultiplier().multiply(spec.getG(), d);
//      priv = new ECSecretBCPGKey(d);
//
//      if (Boolean.TRUE.equals(params.get(PARAM_SIGNATURE))) {
//        ECDSAPublicBCPGKey pub = new ECDSAPublicBCPGKey(ECNamedCurveTable.getOID(curve), q);
//        if (pub.getCurveOID() == CryptlibObjectIdentifiers.curvey25519) {
//          pkp = new PublicKeyPacket(PublicKeyAlgorithmTags.EDDSA, new Date(), pub);
//        } else {
//          pkp = new PublicKeyPacket(PublicKeyAlgorithmTags.ECDSA, new Date(), pub);
//        }
//      } else {
//        ECDHPublicBCPGKey pub = new ECDHPublicBCPGKey(ECNamedCurveTable.getOID(curve), q, HashAlgorithmTags.SHA512, SymmetricKeyAlgorithmTags.AES_256);
//        pkp = new PublicKeyPacket(PublicKeyAlgorithmTags.ECDH, new Date(), pub);
//      }
//    } else if (params.get(PARAM_PRIVATE) instanceof RSAPrivateKey && params.get(PARAM_PUBLIC) instanceof RSAPublicKey) {
//      RSAPublicKey rsapub = (RSAPublicKey) params.get(PARAM_PUBLIC);
//      RSAPrivateKey rsapriv = (RSAPrivateKey) params.get(PARAM_PRIVATE);
//
//      //
//      // Extract encoded key
//      //
//
//      if (!"DER".equals(rsapriv.getFormat()) || null == rsapriv.getEncoded()) {
//        throw new WarpScriptException(getName() + " missing encoded key.");
//      }
//
//      ASN1Sequence seq;
//
//      try {
//        seq = ASN1Sequence.getInstance(DERSequence.fromByteArray(rsapriv.getEncoded()));
//      } catch (IOException ioe) {
//        throw new WarpScriptException(getName() + " error deserializing key.", ioe);
//      }
//
//      priv = new RSASecretBCPGKey(rsapriv.getPrivateExponent(), ASN1Integer.getInstance(seq.getObjectAt(4)).getValue(), ASN1Integer.getInstance(seq.getObjectAt(5)).getValue());
//      BCPGKey pub = new RSAPublicBCPGKey(rsapub.getModulus(), rsapub.getPublicExponent());
//
//      int purpose = PublicKeyAlgorithmTags.RSA_GENERAL;
//      master = true;
//
//      System.out.println("ENC=" + params.get(PARAM_ENCRYPTION));
//      System.out.println("SIG=" + params.get(PARAM_SIGNATURE));
//
//      boolean forEnc = (null == params.get(PARAM_ENCRYPTION) && null == params.get(PARAM_SIGNATURE)) || Boolean.TRUE.equals(params.get(PARAM_ENCRYPTION));
//      boolean forSig = (null == params.get(PARAM_ENCRYPTION) && null == params.get(PARAM_SIGNATURE)) || Boolean.TRUE.equals(params.get(PARAM_SIGNATURE));
//
//      if (!forEnc || !forSig) {
//        master = false;
//        if (forSig) {
//          purpose = PublicKeyAlgorithmTags.RSA_SIGN;
//        } else if (forEnc) {
//          purpose = PublicKeyAlgorithmTags.RSA_SIGN;
//        }
//      }
//
//      pkp = new PublicKeyPacket(purpose, new Date(), pub);
//    } else {
//      PGPKeyRingGenerator generator = null;
//      try {
//        RSAKeyPairGenerator generator1 = new RSAKeyPairGenerator();
//        generator1.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), getSecureRandom(), keySize, 12));
//        BcPGPKeyPair signingKeyPair = new BcPGPKeyPair(PGPPublicKey.RSA_SIGN, generator1.generateKeyPair(), new Date());
//        BcPGPKeyPair encryptionKeyPair = new BcPGPKeyPair(PGPPublicKey.RSA_ENCRYPT, generator1.generateKeyPair(), new Date());
//        PGPSignatureSubpacketGenerator signatureSubpacketGenerator = new PGPSignatureSubpacketGenerator();
//        signatureSubpacketGenerator.setKeyFlags(false, KeyFlags.SIGN_DATA | KeyFlags.CERTIFY_OTHER);
//        signatureSubpacketGenerator.setPreferredSymmetricAlgorithms(false, getPreferredEncryptionAlgorithms());
//        signatureSubpacketGenerator.setPreferredHashAlgorithms(false, getPreferredHashingAlgorithms());
//        signatureSubpacketGenerator.setPreferredCompressionAlgorithms(false, getPreferredCompressionAlgorithms());
//
//        PGPSignatureSubpacketGenerator encryptionSubpacketGenerator = new PGPSignatureSubpacketGenerator();
//        encryptionSubpacketGenerator.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);
//
//        generator = new PGPKeyRingGenerator(PGPPublicKey.RSA_SIGN, signingKeyPair, userId, new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1), signatureSubpacketGenerator.generate(), null, new BcPGPContentSignerBuilder(PGPPublicKey.RSA_SIGN, HashAlgorithmTags.SHA256), new BcPBESecretKeyEncryptorBuilder(getEncryptionAlgorithm()).build(password.toCharArray()));
//        generator.addSubKey(encryptionKeyPair, encryptionSubpacketGenerator.generate(), null);
//      } catch (PGPException e) {
//        generator = null;
//      }
//
//      boolean result = true;
//      PGPPublicKeyRing publicKeyRing = generator.generatePublicKeyRing();
//      PGPSecretKeyRing secretKeyRing = generator.generateSecretKeyRing();
//
//      OutputStream publicKey = new ByteArrayOutputStream();
//      OutputStream secretKey = new ByteArrayOutputStream();
//
//      try( OutputStream targetStream = new ArmoredOutputStream(publicKey) ) {
//        publicKeyRing.encode(targetStream);
//      } catch (IOException e) {
//        result &= false;
//      }
//      try( OutputStream targetStream = new ArmoredOutputStream(secretKey) ) {
//        PGPSecretKeyRingCollection secretKeyRingCollection = new PGPSecretKeyRingCollection(Arrays.asList(secretKeyRing));
//        secretKeyRingCollection.encode(targetStream);
//      } catch (IOException | PGPException e) {
//        result &= false;
//      }
//      return result;
//
//      /*
//      // Generate a new key
//      RSAKeyPairGenerator  kpg = new RSAKeyPairGenerator();
//
//      // Boilerplate RSA parameters, no need to change anything
//      // except for the RSA key-size (2048). You can use whatever key-size makes sense for you -- 4096, etc.
//      int keysize = 3072;
//      long exponent = 0x10001L;
//      int certainty = 12;
//      int s2kcount = 0xc0;
//      String id = "foo@warp";
//      char[] pass = "foo".toCharArray();
//
//      kpg.init(new RSAKeyGenerationParameters(BigInteger.valueOf(exponent), new SecureRandom(), keysize, certainty));
//
//      // First create the master (signing) key with the generator.
//      PGPKeyPair rsakp_sign = new BcPGPKeyPair(PGPPublicKey.RSA_SIGN, kpg.generateKeyPair(), new Date());
//      // Then an encryption subkey.
//      PGPKeyPair rsakp_enc = new BcPGPKeyPair(PGPPublicKey.RSA_ENCRYPT, kpg.generateKeyPair(), new Date());
//
//      // Add a self-signature on the id
//      PGPSignatureSubpacketGenerator signhashgen = new PGPSignatureSubpacketGenerator();
//
//      // Add signed metadata on the signature.
//      // 1) Declare its purpose
//      signhashgen.setKeyFlags(false, KeyFlags.SIGN_DATA|KeyFlags.CERTIFY_OTHER);
//      // 2) Set preferences for secondary crypto algorithms to use when sending messages to this key.
//      signhashgen.setPreferredSymmetricAlgorithms
//          (false, new int[] {
//              SymmetricKeyAlgorithmTags.AES_256,
//              SymmetricKeyAlgorithmTags.AES_192,
//              SymmetricKeyAlgorithmTags.AES_128
//          });
//      signhashgen.setPreferredHashAlgorithms
//          (false, new int[] {
//              HashAlgorithmTags.SHA256,
//              HashAlgorithmTags.SHA1,
//              HashAlgorithmTags.SHA384,
//              HashAlgorithmTags.SHA512,
//              HashAlgorithmTags.SHA224,
//          });
//      // 3) Request senders add additional checksums to the message (useful when verifying unsigned messages.)
//      signhashgen.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION);
//
//      // Create a signature on the encryption subkey.
//      PGPSignatureSubpacketGenerator enchashgen = new PGPSignatureSubpacketGenerator();
//      // Add metadata to declare its purpose
//      enchashgen.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS|KeyFlags.ENCRYPT_STORAGE);
//
//      // Objects used to encrypt the secret key.
//      PGPDigestCalculator sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
//      PGPDigestCalculator sha256Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256);
//
//      // bcpg 1.48 exposes this API that includes s2kcount. Earlier versions use a default of 0x60.
//      PBESecretKeyEncryptor pske = (new BcPBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha256Calc, s2kcount)).build(pass);
//
//      // Finally, create the keyring itself. The constructor takes parameters that allow it to generate the self signature.
//      PGPKeyRingGenerator keyRingGen =
//          new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, rsakp_sign,
//       id, sha1Calc, signhashgen.generate(), null,
//           new BcPGPContentSignerBuilder(rsakp_sign.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1), pske);
//
//      // Add our encryption subkey, together with its signature.
//      keyRingGen.addSubKey(rsakp_enc, enchashgen.generate(), null);
//      long keyid = rsakp_enc.getKeyID();
//      PGPSecretKey psk = keyRingGen.generateSecretKeyRing().getSecretKey(keyid);
//      return keyRingGen;
//*/
//    }
//
//    try {
//      PGPKeyRingGenerator krg = new PGPKeyRingGenerator();
//
//      PGPPublicKey pubKey = new PGPPublicKey(pkp, new BcKeyFingerprintCalculator());
//      SecretKeyPacket skp = null;
//
//      PBESecretKeyEncryptor encryptor = null;
//
//      if (null != passphrase) {
//        encryptor = new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)).build(passphrase.toCharArray());
//        byte[] keyData = priv.getEncoded();
//        keyData = encryptor.encryptKeyData(keyData, 0, keyData.length);
//        skp = new SecretKeyPacket(pkp, encryptor.getAlgorithm(), encryptor.getS2K(), encryptor.getS2K().getIV(), keyData);
//      } else {
//        skp = new SecretKeyPacket(pkp, SymmetricKeyAlgorithmTags.NULL, null, null, priv.getEncoded());
//      }
//
//      PGPPrivateKey ppk = new PGPPrivateKey(0, pkp, priv);
//      //PGPSecretKey secret = new PGPSecretKey(skp, pubKey);
//      PGPDigestCalculator checksumCalculator = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
//      System.out.println("MASTER=" + master);
//      PGPSecretKey secret = new PGPSecretKey(ppk, pubKey, checksumCalculator, master, encryptor);
//
//      ByteArrayOutputStream out = new ByteArrayOutputStream();
//      ArmoredOutputStream armored = new ArmoredOutputStream(out);
//
//      try {
//        secret.encode(armored);
//        armored.close();
//        stack.push(new String(out.toByteArray(), StandardCharsets.UTF_8));
//      } catch (IOException ioe) {
//        throw new WarpScriptException(getName() + " error while serializing PGP secret key ring.", ioe);
//      }
//    } catch (PGPException pge) {
//      throw new WarpScriptException(getName() + " error generating PGP key.", pge);
//    }
//
//    return stack;

  }
}
