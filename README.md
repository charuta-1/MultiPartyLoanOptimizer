# SmartSplit Pro - Minimal Spring Boot scaffold

This repository contains a minimal Spring Boot + Maven scaffold for "SmartSplit Pro" demonstrating a typical MVC layout.

What is included
- `pom.xml` — Maven configuration with Spring Boot, Thymeleaf, JPA, and H2.
- `SmartSplitProApplication.java` — Spring Boot application entry point.
- `model/` — `User`, `Transaction`, `Balance` (entities / DTO).
- `repository/` — Spring Data JPA repositories.
- `service/` — `TransactionService` with sample balance computation and naive settlement optimizer.
- `controller/` — `HomeController` and `TransactionController` with Thymeleaf views.
- `resources/templates/` — Thymeleaf HTML templates.
- `resources/static/css/style.css` — simple styles.
- `resources/application.properties` — H2 in-memory DB + JPA settings.

How to build & run
1. Ensure Java 17+ and Maven are installed and on PATH.
2. From project root run:

```powershell
mvn package -DskipTests
mvn spring-boot:run
```

Open http://localhost:8080 to view the dashboard. H2 console is at http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:smartsplitdb`, user `sa`, empty password).

Next steps
- Add authentication, user management, and proper relations between entities.
- Implement robust settlement optimization algorithms.
- Add tests and CI.

