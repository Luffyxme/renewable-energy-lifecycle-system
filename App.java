import java.sql.*;
import java.util.*;

/* ---------- 1. Custom exceptions (Module 3) ---------- */
class InvalidStageTransitionException extends Exception {
    InvalidStageTransitionException(String msg) { super(msg); }
}
class MissingApprovalException extends Exception {
    MissingApprovalException(String msg) { super(msg); }
}
class DuplicateProjectException extends Exception {
    DuplicateProjectException(String msg) { super(msg); }
}

/* ---------- 2. Interfaces ---------- */
interface Notifiable { void notifyUser(String message); }
interface Reportable { String generateReport(); }

/* ---------- 3. User hierarchy: inheritance + polymorphism ---------- */
abstract class User implements Notifiable {
    private final int userId;
    private final String name;
    private final String passwordHash; // never store plain text
    User(int userId, String name, String passwordHash) {
        this.userId = userId; this.name = name; this.passwordHash = passwordHash;
    }
    public int getUserId() { return userId; }
    public String getName() { return name; }
    boolean login(String hash) { return passwordHash.equals(hash); }
    abstract boolean canPerform(String action); // each role decides for itself
    public void notifyUser(String message) { System.out.println("[" + name + "] " + message); }
}
class ProjectManager extends User {
    ProjectManager(int id, String n, String h) { super(id, n, h); }
    boolean canPerform(String a) {
        return a.equals("CREATE_PROJECT") || a.equals("APPROVE_STAGE") || a.equals("ASSIGN_TASK");
    }
}
class SiteEngineer extends User {
    SiteEngineer(int id, String n, String h) { super(id, n, h); }
    boolean canPerform(String a) { return a.equals("UPDATE_TASK") || a.equals("LOG_MAINTENANCE"); }
}
class Admin extends User {
    Admin(int id, String n, String h) { super(id, n, h); }
    boolean canPerform(String a) { return a.equals("MANAGE_USERS"); }
}

/* ---------- 4. Stage and Task ---------- */
enum Status { PLANNED, IN_PROGRESS, ON_HOLD, COMPLETED, CANCELLED }
class Stage {
    final String name;
    Status status = Status.PLANNED;
    boolean approved = false; // set by a Project Manager
    Stage(String name) { this.name = name; }
}
class Task {
    final String title; final int assignedTo; Status status = Status.PLANNED;
    Task(String title, int assignedTo) { this.title = title; this.assignedTo = assignedTo; }
}

/* ---------- 5. Abstract Project with stage-gate rule ---------- */
abstract class Project implements Reportable {
    static final String[] STAGES = {"Planning", "Approval", "Installation",
        "Commissioning", "Operation", "Maintenance"};
    protected final String name;
    protected final double capacityMW;
    protected final List<Stage> stages = new ArrayList<>();
    protected final List<Task> tasks = new ArrayList<>();
    private int current = 0;
    Project(String name, double capacityMW) {
        this.name = name; this.capacityMW = capacityMW;
        for (String s : STAGES) stages.add(new Stage(s));
        stages.get(0).status = Status.IN_PROGRESS;
    }
    abstract double calcOutput(); // MWh per year, type specific
    void approveStage(User by) throws MissingApprovalException {
        if (!by.canPerform("APPROVE_STAGE"))
            throw new MissingApprovalException(by.getName() + " may not approve stages");
        stages.get(current).approved = true;
    }
    /** Stage gate: current stage must be completed AND approved; no skipping. */
    void advanceStage() throws InvalidStageTransitionException, MissingApprovalException {
        Stage now = stages.get(current);
        if (now.status != Status.COMPLETED)
            throw new InvalidStageTransitionException(now.name + " is not completed yet");
        if (!now.approved)
            throw new MissingApprovalException(now.name + " has no manager approval");
        if (current == stages.size() - 1)
            throw new InvalidStageTransitionException("Already at the final stage");
        current++;
        stages.get(current).status = Status.IN_PROGRESS;
    }
    void completeCurrentStage() { stages.get(current).status = Status.COMPLETED; }
    String currentStage() { return stages.get(current).name; }
    public String generateReport() {
        long done = stages.stream().filter(s -> s.status == Status.COMPLETED).count();
        return name + ": " + capacityMW + " MW, stage " + currentStage()
            + ", " + done + "/" + stages.size() + " stages completed";
    }
}
class SolarProject extends Project {
    SolarProject(String n, double mw) { super(n, mw); }
    double calcOutput() { return capacityMW * 1700; } // ~1700 full-load hours per year
}
class WindProject extends Project {
    WindProject(String n, double mw) { super(n, mw); }
    double calcOutput() { return capacityMW * 2800; }
}
class HydroProject extends Project {
    HydroProject(String n, double mw) { super(n, mw); }
    double calcOutput() { return capacityMW * 4000; }
}

/* ---------- 6. Database: all-or-nothing save with JDBC (Module 4) ---------- */
class ProjectDao {
    private final Connection con;
    ProjectDao(Connection con) { this.con = con; }
    void saveStageAndTask(int projectId, String stage, String status,
                          String taskTitle, int userId) throws SQLException {
        String upStage = "UPDATE stages SET status = ? WHERE project_id = ? AND name = ?";
        String addTask = "INSERT INTO tasks (stage_id, user_id, title, status) "
            + "SELECT stage_id, ?, ?, 'PLANNED' FROM stages "
            + "WHERE project_id = ? AND name = ?";
        boolean oldMode = con.getAutoCommit();
        con.setAutoCommit(false); // start the transaction
        try (PreparedStatement a = con.prepareStatement(upStage);
             PreparedStatement b = con.prepareStatement(addTask)) {
            a.setString(1, status); a.setInt(2, projectId); a.setString(3, stage);
            a.executeUpdate();
            b.setInt(1, userId); b.setString(2, taskTitle);
            b.setInt(3, projectId); b.setString(4, stage);
            b.executeUpdate();
            con.commit(); // both saved together ...
        } catch (SQLException e) {
            con.rollback(); // ... or neither is saved
            throw e;
        } finally {
            con.setAutoCommit(oldMode);
        }
    }
    void writeAudit(int userId, String action, String entity) throws SQLException {
        try (PreparedStatement p = con.prepareStatement(
                "INSERT INTO audit_log (user_id, action, entity, logged_at) "
                + "VALUES (?, ?, ?, NOW())")) {
            p.setInt(1, userId); p.setString(2, action); p.setString(3, entity); p.executeUpdate();
        }
    }
}

public class App {
    public static void main(String[] args) throws Exception {
        User pm = new ProjectManager(1, "Divyansh", "hash1");
        Project p = new SolarProject("Rajasthan Solar Park", 50);
        try {
            p.advanceStage(); // not completed: must be rejected
        } catch (InvalidStageTransitionException e) {
            System.out.println("Rejected: " + e.getMessage());
        }
        p.completeCurrentStage();
        try { p.advanceStage(); } // completed but not approved
        catch (MissingApprovalException e) { System.out.println("Rejected: " + e.getMessage()); }
        p.approveStage(pm);
        p.advanceStage();
        System.out.println(p.generateReport() + ", output " + p.calcOutput() + " MWh/yr");
        pm.notifyUser("Stage moved to " + p.currentStage());
    }
}
