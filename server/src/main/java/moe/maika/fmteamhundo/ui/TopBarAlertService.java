package moe.maika.fmteamhundo.ui;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class TopBarAlertService {

    interface AlertListener {
        void onAlertChanged(String message);
    }

    private static final Set<AlertListener> listeners = new CopyOnWriteArraySet<>();
    private static volatile String message = "";

    private TopBarAlertService() { }

    static String getMessage() {
        return message;
    }

    static void setMessage(String newMessage) {
        message = normalize(newMessage);
        listeners.forEach(listener -> listener.onAlertChanged(message));
    }

    static void clearMessage() {
        setMessage("");
    }

    static void addListener(AlertListener listener) {
        listeners.add(listener);
    }

    static void removeListener(AlertListener listener) {
        listeners.remove(listener);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
