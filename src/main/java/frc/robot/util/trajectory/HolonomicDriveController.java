package frc.robot.util.trajectory;

import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import lombok.Getter;
import lombok.Setter;

public class HolonomicDriveController {
  private final PIDController linearController;
  private final PIDController thetaController;

  private HolonomicDriveState currentState = null;

  /** -- SETTER -- Set tolerance for goal state */
  @Setter private HolonomicDriveState goalTolerance = null;

  /** -- SETTER -- Set goal state */
  @Setter private HolonomicDriveState goalState = null;

  @Getter private Pose2d poseError;

  /** -- SETTER -- Set tolerance of controller with Pose2d */
  @Setter private Pose2d controllerTolerance = null;

  public HolonomicDriveController(
      double linearKp, double linearKd, double thetaKp, double thetaKd) {
    linearController = new PIDController(linearKp, 0, linearKd);
    thetaController = new PIDController(thetaKp, 0, thetaKd);

    this.thetaController.enableContinuousInput(-Math.PI, Math.PI);
  }

  /** Reset all controllers */
  public void resetControllers() {
    linearController.reset();
    thetaController.reset();
  }

  public void resetThetaController() {
    thetaController.reset();
  }

  /** Set PID values */
  public void setPID(double linearKp, double linearKd, double thetaKp, double thetaKd) {
    linearController.setPID(linearKp, 0, linearKd);
    thetaController.setPID(thetaKp, 0, thetaKd);
  }

  /** Calculate robot relative chassis speeds */
  public ChassisSpeeds calculate(
      HolonomicDriveState currentState, HolonomicDriveState setpointState) {
    this.currentState = currentState;
    Pose2d poseRef = setpointState.pose();
    poseError = poseRef.relativeTo(currentState.pose());

    // Calculate feedback velocities (based on position error).
    Translation2d currToStateTranslation =
        poseRef.getTranslation().minus(currentState.pose().getTranslation());
    double linearFeedback =
        linearController.calculate(
            0, currentState.pose().getTranslation().getDistance(poseRef.getTranslation()));
    double xFeedback = linearFeedback * (currToStateTranslation.getAngle().getCos());
    double yFeedback = linearFeedback * (currToStateTranslation.getAngle().getSin());
    double thetaFeedback =
        thetaController.calculate(
            currentState.pose().getRotation().getRadians(), poseRef.getRotation().getRadians());

    // Return next output.
    return ChassisSpeeds.fromFieldRelativeSpeeds(
        setpointState.velocityX() + xFeedback,
        setpointState.velocityY() + yFeedback,
        setpointState.angularVelocity() + thetaFeedback,
        currentState.pose().getRotation());
  }

  /** Within tolerance of current goal */
  public boolean atGoal() {
    Pose2d goalPoseError = goalState.pose().relativeTo(currentState.pose());
    boolean withinPoseTolerance =
        goalPoseError.getTranslation().getNorm() <= goalTolerance.pose().getTranslation().getNorm()
            && Math.abs(goalPoseError.getRotation().getRadians())
                <= goalTolerance.pose().getRotation().getRadians();
    boolean withinVelocityTolerance =
        Math.abs(currentState.velocityX() - goalState.velocityX()) < goalTolerance.velocityX()
            && Math.abs(currentState.velocityY() - goalState.velocityY())
                < goalTolerance.velocityY()
            && Math.abs(currentState.angularVelocity() - goalState.angularVelocity())
                < goalTolerance.angularVelocity();
    return withinPoseTolerance && withinVelocityTolerance;
  }

  public record HolonomicDriveState(
      Pose2d pose, double velocityX, double velocityY, double angularVelocity) {}
}
