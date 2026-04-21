package moe.maika.fmteamhundo.data.entities;

import java.io.Serializable;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a team in the FM Team Hundo system.
 * TeamId 0 is reserved to indicate "no team".
 * @author sg4e
 */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "teams")
public class Team implements Serializable {
    
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer teamId;
    
    private String name;
    
    public Team(String name) {
        this.name = name;
    }
}
