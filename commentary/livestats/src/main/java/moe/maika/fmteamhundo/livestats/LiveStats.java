package moe.maika.fmteamhundo.livestats;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class LiveStats extends Application {
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Label placeholder = new Label("Live Stats");
        StackPane root = new StackPane(placeholder);
        root.setPadding(new Insets(24));

        Scene scene = new Scene(root, 800, 600);
        stage.setTitle("FM Team Hundo Live Stats");
        stage.setScene(scene);
        stage.show();
    }
}
