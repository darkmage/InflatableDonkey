/*
 * The MIT License
 *
 * Copyright 2016 Ahseya.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.horrorho.inflatabledonkey.pcs.zone;

import com.github.horrorho.inflatabledonkey.crypto.GCMDataA;
import com.github.horrorho.inflatabledonkey.crypto.ec.ECAssistant;
import com.github.horrorho.inflatabledonkey.crypto.ec.ECurves;
import com.github.horrorho.inflatabledonkey.crypto.ec.key.ECPrivateKey;
import com.github.horrorho.inflatabledonkey.crypto.ec.key.ECPublicKey;
import com.github.horrorho.inflatabledonkey.data.der.DERUtils;
import com.github.horrorho.inflatabledonkey.data.der.EncryptedKey;
import com.github.horrorho.inflatabledonkey.data.der.KeySet;
import com.github.horrorho.inflatabledonkey.data.der.NOS;
import com.github.horrorho.inflatabledonkey.data.der.ProtectionInfo;
import com.github.horrorho.inflatabledonkey.data.der.ProtectionObject;
import com.github.horrorho.inflatabledonkey.pcs.key.Key;
import com.github.horrorho.inflatabledonkey.pcs.key.KeyID;
import com.github.horrorho.inflatabledonkey.pcs.key.imports.KeyImports;
import com.github.horrorho.inflatabledonkey.pcs.service.ServiceKeySet;
import com.github.horrorho.inflatabledonkey.pcs.service.ServiceKeySetBuilder;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import static java.util.stream.Collectors.toList;
import javax.annotation.concurrent.Immutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PZAssistant.
 *
 * @author Ahseya
 */
@Immutable
public final class PZAssistant {

    public static PZAssistant instance() {
        return DEFAULT_INSTANCE;
    }

    private static final Logger logger = LoggerFactory.getLogger(PZAssistant.class);

    // TODO inject via Property
    private static final PZAssistant DEFAULT_INSTANCE = new PZAssistant(
            ECAssistant.fieldLengthToCurveName(ECurves.defaults()),
            PZDataUnwrap.instance(),
            true
    );

    private final IntFunction<Optional<String>> fieldLengthToCurveName;
    private final PZDataUnwrap dataUnwrap;
    private final boolean useCompactKeys;

    public PZAssistant(
            IntFunction<Optional<String>> fieldLengthToCurveName,
            PZDataUnwrap dataUnwrap,
            boolean useCompactKeys) {

        this.fieldLengthToCurveName = Objects.requireNonNull(fieldLengthToCurveName, "fieldLengthToCurveName");
        this.dataUnwrap = Objects.requireNonNull(dataUnwrap, "dataUnwrap");
        this.useCompactKeys = useCompactKeys;
    }

    public List<byte[]> masterKeys(ProtectionInfo protectionInfo, LinkedHashMap<KeyID, Key<ECPrivateKey>> keys) {
        return masterKeys(protectionInfo.encryptedKeys().encryptedKeySet(), keys);
    }

    List<byte[]> masterKeys(List<EncryptedKey> encryptedKeySet, LinkedHashMap<KeyID, Key<ECPrivateKey>> keys) {
        return encryptedKeys(encryptedKeySet)
                .stream()
                .map(ek -> unwrapKey(ek, keys))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    List<EncryptedKey> encryptedKeys(List<EncryptedKey> encryptedKeySet) {
        logger.debug("-- encryptedKeys() - encrypted key set: {}", encryptedKeySet);
        return encryptedKeySet.stream()
                .collect(toList());
    }

    Optional<byte[]> unwrapKey(EncryptedKey encryptedKey, LinkedHashMap<KeyID, Key<ECPrivateKey>> keys) {
        Optional<byte[]> key = importPublicKey(encryptedKey.masterKey().key())
                .map(Key::keyID)
                .map(keys::get)
                .map(Key::keyData)
                .map(ECPrivateKey::d)
                .map(d -> dataUnwrap.apply(encryptedKey.wrappedKey(), d));
        if (!key.isPresent()) {
            logger.warn("-- unwrapKey - failed to unwrap key: {}", encryptedKey);
        }
        return key;
    }

    Optional<Key<ECPublicKey>> importPublicKey(byte[] keyData) {
        return KeyImports.importPublicKey(fieldLengthToCurveName, useCompactKeys)
                .apply(keyData);
    }

    public List<Key<ECPrivateKey>> keys(ProtectionInfo protectionInfo, List<byte[]> keys) {
        return protectionInfo.data()
                .map(bs -> keys(bs, keys))
                .orElse(Collections.emptyList());
    }

    List<Key<ECPrivateKey>> keys(byte[] encryptedProtectionInfoData, List<byte[]> keys) {
        return keys.stream()
                .map(u -> {
                    try {
                        return GCMDataA.decrypt(u, encryptedProtectionInfoData);
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        logger.debug("-- decrypt() - exception: {}", ex.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .map(this::keys)
                .findFirst()
                .orElseGet(() -> Collections.emptyList());
    }

    List<Key<ECPrivateKey>> keys(byte[] protectionInfoData) {
        return DERUtils.parse(protectionInfoData, ProtectionObject::new)
                .flatMap(ProtectionObject::getMasterKeySet)
                .map(this::keys)
                .orElse(Collections.emptyList());
    }

    List<Key<ECPrivateKey>> keys(List<NOS> masterKeySet) {
        List<Key<ECPrivateKey>> keys = masterKeySet.stream()
                .map(this::keys)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
        logger.debug("-- keys() - keys: {}", keys);
        return keys;
    }

    Optional<Collection<Key<ECPrivateKey>>> keys(NOS masterKey) {
        return DERUtils.parse(masterKey.key(), KeySet::new)
                .flatMap(ServiceKeySetBuilder::build)
                .map(ServiceKeySet::keys);
    }
}
