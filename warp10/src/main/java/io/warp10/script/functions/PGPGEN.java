//
//   Copyright 2023-2024  SenX S.A.S.
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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.KeyGenerationParameters;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECNamedDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.bouncycastle.math.ec.WNafUtil;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;

import io.warp10.continuum.store.Constants;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

public class PGPGEN extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  private static final String PARAM_CURVE = "curve";
  private static final String PARAM_D = "d";
  private static final String PARAM_SEED = "seed";
  private static final String PARAM_ID = "id";
  private static final String PARAM_PASSPHRASE = "passphrase";
  private static final String PARAM_DATE = "date";
  private static final String PARAM_NAFWEIGHTCHECK = "nafweightcheck";

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

    int s2kcount = 241;

    String id = String.valueOf(params.getOrDefault(PARAM_ID,UUID.randomUUID().toString()));

    try {
      //
      // Check that the specified curve is OpenPGP compatible
      //

      String curve = (String) (params.get(PARAM_CURVE) instanceof String ? params.get(PARAM_CURVE) : null);

      boolean nafWeightCheck = Boolean.TRUE.equals(params.get(PARAM_NAFWEIGHTCHECK));

      BigInteger d = null;
      byte[] seed = null;

      if (params.get(PARAM_D) instanceof String) {
        String dstr = (String) params.get(PARAM_D);

        if (dstr.startsWith("0x")) {
          d = new BigInteger(dstr.substring(2), 16);
        } else {
          d = new BigInteger(dstr);
        }
      }

      if (params.get(PARAM_SEED) instanceof byte[]) {
        seed = (byte[]) params.get(PARAM_SEED);
      }

      SecureRandom random = new SecureRandom();

      //
      // Master key pair, used for signing subkeys
      //
      BcPGPKeyPair masterkp = null;

      //
      // Encryption key pair
      //
      BcPGPKeyPair enckp = null;

      Date date = new Date(0L);

      if (params.get(PARAM_DATE) instanceof Long) {
        date = new Date(((Long) params.get(PARAM_DATE)).longValue() / Constants.TIME_UNITS_PER_MS);
      }

      ECNamedCurveParameterSpec spec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec(curve);
      //X9ECParameters spec = CustomNamedCurves.getByName(curve);
      ASN1ObjectIdentifier curveoid = ECNamedCurveTable.getOID(spec.getName());
      ECNamedDomainParameters domainParams = new ECNamedDomainParameters(curveoid, spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
      ECKeyGenerationParameters eckgp = new ECKeyGenerationParameters(domainParams, random);

      ECKeyPairGenerator eckpg = new ECKeyPairGenerator();
      eckpg.init(eckgp);

      if (CryptlibObjectIdentifiers.curvey25519.equals(curveoid)) { // "curve25519".equals(curve)) {
        AsymmetricCipherKeyPair ackp = null;

        if (null != seed) {
          if (seed.length != Ed25519PrivateKeyParameters.KEY_SIZE) {
            throw new WarpScriptException(getName() + " seed MUST be of size " + Ed25519PrivateKeyParameters.KEY_SIZE + ".");
          }

          Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(Arrays.copyOf(seed, seed.length));
          Ed25519PublicKeyParameters publicKey = privateKey.generatePublicKey();
          ackp = new AsymmetricCipherKeyPair(publicKey, privateKey);
        } else {
          //
          // Generate a random key
          //
          Ed25519KeyPairGenerator edkpg = new Ed25519KeyPairGenerator();
          edkpg.init(new KeyGenerationParameters(random, 0));
          ackp = edkpg.generateKeyPair();
        }

        masterkp = new BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA, ackp, date);
      } else {
        AsymmetricCipherKeyPair ackp = null;

        if (null != d) {
          BigInteger n = eckgp.getDomainParameters().getN();
          int nBitLength = n.bitLength();
          int minWeight = nBitLength >>> 2;

          if (d.compareTo(BigInteger.ONE) < 0  || (d.compareTo(n) >= 0)) {
            throw new WarpScriptException(getName() + " private key should be positive and less than curve order (" + n + ").");
          }

          // (non-zero entries in signed-binary, non-adjacent form (NAF) representation)
          if (nafWeightCheck && WNafUtil.getNafWeight(d) < minWeight) {
            throw new WarpScriptException(getName() + " private key has a low NAF weight ( < " + minWeight + ").");
          }

          ECPoint Q = new FixedPointCombMultiplier().multiply(eckgp.getDomainParameters().getG(), d);
          ackp = new AsymmetricCipherKeyPair(new ECPublicKeyParameters(Q, eckgp.getDomainParameters()), new ECPrivateKeyParameters(d, eckgp.getDomainParameters()));
        } else {
          ackp = eckpg.generateKeyPair();
        }

        masterkp = new BcPGPKeyPair(PublicKeyAlgorithmTags.ECDSA, ackp, date);
      }

      eckpg.init(eckgp);
      AsymmetricCipherKeyPair ackp = null;

      if (null != d) {
        BigInteger n = eckgp.getDomainParameters().getN();
        int nBitLength = n.bitLength();
        int minWeight = nBitLength >>> 2;

        if (CryptlibObjectIdentifiers.curvey25519.equals(curveoid)) {
          byte[] buf = new byte[X25519PrivateKeyParameters.KEY_SIZE];
          byte[] dbytes = d.toByteArray();
          org.bouncycastle.util.Arrays.reverseInPlace(dbytes);

          if (dbytes.length > buf.length) {
            throw new WarpScriptException(getName() + " private key exceeds selected curve key size (" + buf.length + " bytes).");
          }

          // copy the private key, padding with 0x00 on the right since it is little-endian encoded
          System.arraycopy(dbytes, 0, buf, 0, dbytes.length);

          //
          // Check that lower 3 bits are 0 (private key must be a multiple of 8 as 8 is the cofactor to avoid leakage in case of small subgroup attacks)
          // Check that bit 255 is 0 and bit 254 is 1 which are there to protect against timing attacks.
          //

          if ((0x07 & (int) buf[0]) != 0) {
            throw new WarpScriptException(getName() + " invalid key, lower 3 bits must be cleared.");
          }

          if (0 != (((int) buf[buf.length - 1]) & 0x80)) {
            throw new WarpScriptException(getName() + " invalid key, bit 255 must be cleared.");
          }

          if (0x40 != (((int) buf[buf.length - 1]) & 0x40)) {
            throw new WarpScriptException(getName() + " invalid key, bit 254 must be set.");
          }

          X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(buf);
          X25519PublicKeyParameters publicKey = privateKey.generatePublicKey();
          ackp = new AsymmetricCipherKeyPair(publicKey, privateKey);
        } else {
          if (d.compareTo(BigInteger.ONE) < 0  || (d.compareTo(n) >= 0)) {
            throw new WarpScriptException(getName() + " private key should be positive and less than curve order (" + n + ").");
          }

          if (nafWeightCheck && WNafUtil.getNafWeight(d) < minWeight) {
            throw new WarpScriptException(getName() + " private key has a low NAF weight ( < " + minWeight + ").");
          }

          ECPoint Q = new FixedPointCombMultiplier().multiply(eckgp.getDomainParameters().getG(), d);
          ackp = new AsymmetricCipherKeyPair(new ECPublicKeyParameters(Q, eckgp.getDomainParameters()), new ECPrivateKeyParameters(d, eckgp.getDomainParameters()));
        }
      } else {
        ackp = eckpg.generateKeyPair();
      }

      enckp = new BcPGPKeyPair(PublicKeyAlgorithmTags.ECDH, ackp, date);

      // Add a self-signature on the id
      PGPSignatureSubpacketGenerator signhashgen = new PGPSignatureSubpacketGenerator();

      // Add signed metadata on the signature.

      // 1) Declare its purpose
      signhashgen.setKeyFlags(false, KeyFlags.SIGN_DATA|KeyFlags.CERTIFY_OTHER);

      // 2) Set preferences for secondary crypto algorithms to use when sending messages to this key.
      signhashgen.setPreferredSymmetricAlgorithms(false, new int[] {
        SymmetricKeyAlgorithmTags.AES_256,
        SymmetricKeyAlgorithmTags.AES_192,
        SymmetricKeyAlgorithmTags.AES_128,
        SymmetricKeyAlgorithmTags.CAMELLIA_256,
        SymmetricKeyAlgorithmTags.CAMELLIA_192,
        SymmetricKeyAlgorithmTags.CAMELLIA_128,
        SymmetricKeyAlgorithmTags.TWOFISH,
        SymmetricKeyAlgorithmTags.CAST5,
        SymmetricKeyAlgorithmTags.BLOWFISH,
        SymmetricKeyAlgorithmTags.SAFER,
        SymmetricKeyAlgorithmTags.IDEA,
        SymmetricKeyAlgorithmTags.TRIPLE_DES,
        SymmetricKeyAlgorithmTags.DES,
        SymmetricKeyAlgorithmTags.NULL,
      });

      signhashgen.setPreferredHashAlgorithms(false, new int[] {
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
      });

      signhashgen.setPreferredCompressionAlgorithms(false, new int[] {
        CompressionAlgorithmTags.BZIP2,
        CompressionAlgorithmTags.ZLIB,
        CompressionAlgorithmTags.ZIP,
        CompressionAlgorithmTags.UNCOMPRESSED
      });

      // 3) Request senders add additional checksums to the message (useful when verifying unsigned messages.)
      signhashgen.setFeature(false, (byte) (Features.FEATURE_MODIFICATION_DETECTION|Features.FEATURE_VERSION_5_PUBLIC_KEY));
      signhashgen.setSignatureCreationTime(true, date);

      // Objects used to encrypt the secret key.
      PGPDigestCalculator digestCalc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);

      // bcpg 1.48 exposes this API that includes s2kcount. Earlier versions use a default of 0x60.
      PBESecretKeyEncryptor pske = (new BcPBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, digestCalc, s2kcount)).build(passphrase.toCharArray());

      // Finally, create the keyring itself. The constructor takes parameters that allow it to generate the self signature.
      PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, masterkp, id, digestCalc, signhashgen.generate(), null, new BcPGPContentSignerBuilder(masterkp.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA512), pske);

      // Create a signature on the encryption subkey.
      PGPSignatureSubpacketGenerator enchashgen = new PGPSignatureSubpacketGenerator();

      // Add metadata to declare its purpose
      int flags = KeyFlags.ENCRYPT_COMMS|KeyFlags.ENCRYPT_STORAGE;
      enchashgen.setKeyFlags(false, flags);
      //enchashgen.setFeature(false, (byte) (Features.FEATURE_MODIFICATION_DETECTION|Features.FEATURE_VERSION_5_PUBLIC_KEY));
      enchashgen.setSignatureCreationTime(true, date);

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
        SymmetricKeyAlgorithmTags.SAFER,
        SymmetricKeyAlgorithmTags.IDEA,
        SymmetricKeyAlgorithmTags.TRIPLE_DES,
        SymmetricKeyAlgorithmTags.DES,
        SymmetricKeyAlgorithmTags.NULL,
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
      });

      enchashgen.setPreferredCompressionAlgorithms(false, new int[] {
        CompressionAlgorithmTags.BZIP2,
        CompressionAlgorithmTags.ZLIB,
        CompressionAlgorithmTags.ZIP,
        CompressionAlgorithmTags.UNCOMPRESSED,
      });

      enchashgen.addSignerUserID(false, id);

      keyRingGen.addSubKey(enckp, enchashgen.generate(), null);

      PGPSecretKeyRing skr =  keyRingGen.generateSecretKeyRing();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ArmoredOutputStream armored = new ArmoredOutputStream(out);
      skr.encode(armored);
      armored.close();
      byte[] data = out.toByteArray();
      stack.push(new String(data, 0, data.length, StandardCharsets.US_ASCII));

      return stack;
    } catch (Throwable e) { // PGPException|IOException
      e.printStackTrace();
      throw new WarpScriptException(getName() + " error while generating PGP key ring.",e);
    }
  }
}
