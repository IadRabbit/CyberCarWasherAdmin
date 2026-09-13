package com.meethack.romhack.camp2026;

import java.util.Arrays;
import java.util.List;

/** Car wash service catalog offered at the POS. */
public final class Services {

    public static final List<Service> CATALOG = Arrays.asList(
            new Service("Quick Wash", 5),
            new Service("Full Wash", 9),
            new Service("Premium Wash + Wax", 14),
            new Service("Interior + Exterior Full", 19)
    );

    private Services() {
    }

    public static final class Service {
        public final String name;
        public final int price;

        public Service(String name, int price) {
            this.name = name;
            this.price = price;
        }
    }
}
