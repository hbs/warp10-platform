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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.gnu.GNUObjectIdentifiers;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGKey;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.ECDHPublicBCPGKey;
import org.bouncycastle.bcpg.ECDSAPublicBCPGKey;
import org.bouncycastle.bcpg.ECSecretBCPGKey;
import org.bouncycastle.bcpg.EdDSAPublicBCPGKey;
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
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.iana.AEADAlgorithm;
import org.bouncycastle.jcajce.spec.EdDSAParameterSpec;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.bouncycastle.math.ec.FixedPointUtil;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
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

import io.warp10.continuum.store.Constants;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

public class PGPGEN extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  private static final String PARAM_CURVE = "curve";
  private static final String PARAM_D = "d";
  private static final String PARAM_ID = "id";
  private static final String PARAM_PASSPHRASE = "passphrase";
  private static final String PARAM_DATE = "date";
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

    int s2kcount = 0x60;
    long exponent = 0x10001L;

    String id = String.valueOf(params.getOrDefault(PARAM_ID,UUID.randomUUID().toString()));

    try {
      //
      // Check that the specified curve is OpenPGP compatible
      //

      String curve = (String) (params.get(PARAM_CURVE) instanceof String ? params.get(PARAM_CURVE) : null);

      if (!(curve instanceof String && "P-256".equals(curve) || "P-384".equals(curve) || "P-521".equals(curve) || "curve25519".equals(curve))) {
        throw new WarpScriptException(getName() + " invalid curve, must be one of 'P-256', 'P-384', 'P-521' or 'curve25519'");
      }

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

      BCPGKey eccpriv_sign;
      BCPGKey eccpriv_enc;
      BCPGKey eccpub_enc;
      BCPGKey eccpub_sign;
      PublicKeyPacket pkp_sign;
      PublicKeyPacket pkp_enc;

      if (!(params.getOrDefault(PARAM_DATE, 0L) instanceof Long)) {
        throw new WarpScriptException(getName() + " invalid date, expected a LONG.");
      }

      Date date = new Date(((Long) params.getOrDefault(PARAM_DATE, System.currentTimeMillis() * Constants.TIME_UNITS_PER_MS)).longValue() / Constants.TIME_UNITS_PER_MS);

      if (CryptlibObjectIdentifiers.curvey25519 == ECNamedCurveTable.getOID(curve)) {
        eccpriv_sign = new EdSecretBCPGKey(d);

        byte[] bi = d.toByteArray();
        if (bi.length > Ed25519.SECRET_KEY_SIZE) {
          throw new WarpScriptException(getName() + " invalid private key size for curve Ed25519, expected " + Ed25519.SECRET_KEY_SIZE + " bytes, was " + bi.length + ".");
        } else if (bi.length < Ed25519.SECRET_KEY_SIZE) {
          byte[] tmp = bi;
          bi = new byte[Ed25519.SECRET_KEY_SIZE];
          System.arraycopy(tmp, 0, bi, bi.length - tmp.length, tmp.length);
        }

        Ed25519PrivateKeyParameters edpkp = new Ed25519PrivateKeyParameters(bi);
        Ed25519PublicKeyParameters edpubkp = edpkp.generatePublicKey();
        byte[] pointEnc = new byte[1 + Ed25519PublicKeyParameters.KEY_SIZE];
        pointEnc[0] = 0x40;
        edpubkp.encode(pointEnc, 1);
        eccpub_sign = new EdDSAPublicBCPGKey(GNUObjectIdentifiers.Ed25519, new BigInteger(1, pointEnc));
        pkp_sign = new PublicKeyPacket(PublicKeyAlgorithmTags.EDDSA, date, eccpub_sign);
      } else {
        eccpriv_sign = new ECSecretBCPGKey(d);
        eccpub_sign = new ECDSAPublicBCPGKey(ECNamedCurveTable.getOID(curve), q);
        pkp_sign = new PublicKeyPacket(PublicKeyAlgorithmTags.ECDSA, date, eccpub_sign);
      }

      eccpriv_enc = new ECSecretBCPGKey(d);
      eccpub_enc = new ECDHPublicBCPGKey(ECNamedCurveTable.getOID(curve), q, HashAlgorithmTags.SHA512, SymmetricKeyAlgorithmTags.AES_256);
      pkp_enc = new PublicKeyPacket(PublicKeyAlgorithmTags.ECDH, date, eccpub_enc);

      PGPPublicKey pub_sign = new PGPPublicKey(pkp_sign, new BcKeyFingerprintCalculator());
      PGPPublicKey pub_enc = new PGPPublicKey(pkp_enc, new BcKeyFingerprintCalculator());
      PGPPrivateKey priv_sign = new PGPPrivateKey(pub_sign.getKeyID(), pkp_sign, eccpriv_sign);
      PGPPrivateKey priv_enc = new PGPPrivateKey(pub_enc.getKeyID(), pkp_enc, eccpriv_enc);

      //
      // The signing and encryption keys are identical to avoid creating a new random key and
      // to also avoir having to cross-certify the keys.
      //

      PGPKeyPair kp_sign = new PGPKeyPair(pub_sign, priv_sign);
      PGPKeyPair kp_enc = new PGPKeyPair(pub_enc, priv_enc);

//      //#################################
//      RSAKeyPairGenerator  kpg = new RSAKeyPairGenerator();
//      kpg.init(new RSAKeyGenerationParameters(BigInteger.valueOf(exponent), new SecureRandom(), keysize, certainty));
//      // First create the master (signing) key with the generator.
//      PGPKeyPair rsakp_sign = new BcPGPKeyPair(PGPPublicKey.RSA_SIGN, kpg.generateKeyPair(), new Date());
////      // Then an encryption subkey.
////      PGPKeyPair rsakp_enc = new BcPGPKeyPair(PGPPublicKey.RSA_ENCRYPT, kpg.generateKeyPair(), new Date());


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
      signhashgen.setFeature(false, (byte) (Features.FEATURE_MODIFICATION_DETECTION|Features.FEATURE_VERSION_5_PUBLIC_KEY));

      // Objects used to encrypt the secret key.
      PGPDigestCalculator sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
      // We need at least 384 bits of hash for some ECC keys
      PGPDigestCalculator sha512Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA512);

      // bcpg 1.48 exposes this API that includes s2kcount. Earlier versions use a default of 0x60.
      PBESecretKeyEncryptor pske = (new BcPBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha512Calc, s2kcount)).build(passphrase.toCharArray());

      // Finally, create the keyring itself. The constructor takes parameters that allow it to generate the self signature.
      PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, kp_sign, id, sha1Calc, signhashgen.generate(), null, new BcPGPContentSignerBuilder(kp_sign.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA512), pske);

      // Create a signature on the encryption subkey.
      PGPSignatureSubpacketGenerator enchashgen = new PGPSignatureSubpacketGenerator();

      // Add metadata to declare its purpose
      int flags = KeyFlags.ENCRYPT_COMMS|KeyFlags.ENCRYPT_STORAGE;
      enchashgen.setKeyFlags(false, flags);
      enchashgen.setFeature(false, (byte) (Features.FEATURE_MODIFICATION_DETECTION|Features.FEATURE_VERSION_5_PUBLIC_KEY));

      enchashgen.setPreferredSymmetricAlgorithms(false, new int[] {
        SymmetricKeyAlgorithmTags.AES_256,
        SymmetricKeyAlgorithmTags.AES_192,
        SymmetricKeyAlgorithmTags.AES_128,
        SymmetricKeyAlgorithmTags.CAMELLIA_256,
        SymmetricKeyAlgorithmTags.CAMELLIA_192,
        SymmetricKeyAlgorithmTags.CAMELLIA_128,
        SymmetricKeyAlgorithmTags.TWOFISH,
        SymmetricKeyAlgorithmTags.CAST5,
        SymmetricKeyAlgorithmTags.BLOWFISH,
        SymmetricKeyAlgorithmTags.TRIPLE_DES,
        SymmetricKeyAlgorithmTags.SAFER,
        SymmetricKeyAlgorithmTags.IDEA,
        SymmetricKeyAlgorithmTags.DES,
        SymmetricKeyAlgorithmTags.NULL,
        /*
        */
      });

      enchashgen.setPreferredHashAlgorithms(false, new int[] {
        HashAlgorithmTags.SHA3_512,
        HashAlgorithmTags.SHA3_384,
        HashAlgorithmTags.SHA3_256,
        HashAlgorithmTags.SHA3_224,
        HashAlgorithmTags.SHA512,
        HashAlgorithmTags.SHA384,
        HashAlgorithmTags.SHA256,
        HashAlgorithmTags.SHA224,
        HashAlgorithmTags.SM3,
        HashAlgorithmTags.TIGER_192,
        HashAlgorithmTags.DOUBLE_SHA,
        HashAlgorithmTags.HAVAL_5_160,
        HashAlgorithmTags.RIPEMD160,
        HashAlgorithmTags.SHA1,
        HashAlgorithmTags.MD5,
        HashAlgorithmTags.MD4,
        HashAlgorithmTags.MD2,
        /*
        */
      });

      enchashgen.setPreferredCompressionAlgorithms(false, new int[] {
        CompressionAlgorithmTags.BZIP2,
        CompressionAlgorithmTags.ZLIB,
        CompressionAlgorithmTags.ZIP,
        CompressionAlgorithmTags.UNCOMPRESSED,
      });

      enchashgen.addSignerUserID(false, id);

      keyRingGen.addSubKey(kp_enc, enchashgen.generate(), null);

      PGPSecretKeyRing skr =  keyRingGen.generateSecretKeyRing();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ArmoredOutputStream armored = new ArmoredOutputStream(out);
      skr.encode(armored);
      armored.close();
      byte[] data = out.toByteArray();
      stack.push(new String(data, 0, data.length, StandardCharsets.US_ASCII));

      return stack;
    } catch (PGPException|IOException e) {
      throw new WarpScriptException(getName() + " error while generating PGP key ring.",e);
    }
  }
}
