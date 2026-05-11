package moe.maika.fmteamhundo.ui;

import java.util.List;

import com.vaadin.flow.component.Component;

import moe.maika.fmteamhundo.api.LibraryUpdate;
import moe.maika.fmteamhundo.state.HundoConstants;

enum StatKind {
    CARDS("cards", "Cards") {
        @Override
        String formatValue(LibraryUpdate snapshot, HundoConstants hundoConstants) {
            return String.format("%d/%d", snapshot.uniqueCardCount(), hundoConstants.getTotalObtainableCards());
        }
    },
    STARCHIPS("starchips", "Starchips") {
        @Override
        String formatValue(LibraryUpdate snapshot, HundoConstants hundoConstants) {
            return Long.toString(snapshot.totalStarchips());
        }
    },
    COST_OF_BUYABLES("cost_of_buyables", "Cost of Buyables") {
        @Override
        String formatValue(LibraryUpdate snapshot, HundoConstants hundoConstants) {
            return Integer.toString(snapshot.totalCostOfBuyables());
        }
    },
    UNBUYABLES("unbuyables", "Unbuyables") {
        @Override
        String formatValue(LibraryUpdate snapshot, HundoConstants hundoConstants) {
            return Integer.toString(snapshot.totalUnbuyables());
        }
    },
    BEWDS("bewds", "BEWDs") {
        @Override
        String formatValue(LibraryUpdate snapshot, HundoConstants hundoConstants) {
            return Integer.toString(snapshot.bewdCount());
        }
    };

    private static final List<StatKind> ALL = List.of(values());

    private final String id;
    private final String label;

    StatKind(String id, String label) {
        this.id = id;
        this.label = label;
    }

    static List<StatKind> all() {
        return ALL;
    }

    String id() {
        return id;
    }

    Component createComponent(LibraryUpdate snapshot, HundoConstants hundoConstants) {
        return ViewSupport.createStat(label, formatValue(snapshot, hundoConstants));
    }

    abstract String formatValue(LibraryUpdate snapshot, HundoConstants hundoConstants);
}
