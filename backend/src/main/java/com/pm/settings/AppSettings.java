package com.pm.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Singleton settings row — id is always 1. */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
@NoArgsConstructor
public class AppSettings {

    @Id
    private int id = 1;

    /**
     * Optional JAVA_HOME to inject into every managed-project process.
     * Null / blank = use whatever the system PATH already provides.
     */
    @Column(name = "java_home", length = 500)
    private String javaHome;

    /**
     * Optional NODE_HOME to inject into every managed-project process.
     * Null / blank = use whatever the system PATH already provides.
     */
    @Column(name = "node_home", length = 500)
    private String nodeHome;
}
