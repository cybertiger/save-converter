/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.cyberiantiger.minecraft.saveconverter;

import java.math.BigInteger;
import java.util.UUID;

/**
 *
 * @author antony
 */
public class Profile {
    private String id;
    private String name;

    public UUID getUUID() {
        BigInteger uuid = new BigInteger(id, 16);
        long lsb = uuid.longValue();
        long msb = uuid.shiftRight(64).longValue();
        return new UUID(msb, lsb);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
