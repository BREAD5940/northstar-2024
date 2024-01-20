// Copyright 2021-2024 FRC 6328
// http://github.com/Mechanical-Advantage
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// version 3 as published by the Free Software Foundation or
// available in the root directory of this project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.

package frc.robot.subsystems.drive;

import static frc.robot.subsystems.drive.DriveConstants.*;

import com.revrobotics.CANSparkBase.IdleMode;
import com.revrobotics.CANSparkLowLevel;
import com.revrobotics.CANSparkLowLevel.PeriodicFrame;
import com.revrobotics.CANSparkMax;
import com.revrobotics.REVLibError;
import com.revrobotics.RelativeEncoder;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.controller.SimpleMotorFeedforward;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.wpilibj.AnalogInput;
import edu.wpi.first.wpilibj.RobotController;
import java.util.Queue;
import java.util.function.Supplier;

public class ModuleIOSparkMax implements ModuleIO {
  // Every 100 times through periodic (2 secs) we reset relative encoder to absolute encoder
  private static final int reSeedIterations = 100;

  private final CANSparkMax driveSparkMax;
  private final CANSparkMax turnSparkMax;
  private final RelativeEncoder driveEncoder;
  private final RelativeEncoder turnRelativeEncoder;
  private final AnalogInput turnAbsoluteEncoder;

  // Queues
  private final Queue<Double> drivePositionQueue;
  private final Queue<Double> turnPositionQueue;

  private final Rotation2d absoluteEncoderOffset;
  private int currentIteration = reSeedIterations;
  private Supplier<Rotation2d> absoluteEncoderValue;

  private final SimpleMotorFeedforward driveFeedforward;
  private final PIDController driveFeedback;
  private final PIDController turnFeedback;
  private Rotation2d angleSetpoint = null; // Setpoint for closed loop control, null for open loop
  private Double speedSetpoint = null; // Setpoint for closed loop control, null for open loop

  public ModuleIOSparkMax(ModuleConfig config) {
    // Init motor & encoder objects
    driveSparkMax = new CANSparkMax(config.driveID(), CANSparkLowLevel.MotorType.kBrushless);
    turnSparkMax = new CANSparkMax(config.turnID(), CANSparkLowLevel.MotorType.kBrushless);
    turnAbsoluteEncoder = new AnalogInput(config.absoluteEncoderChannel());
    absoluteEncoderOffset = config.absoluteEncoderOffset();
    driveEncoder = driveSparkMax.getEncoder();
    turnRelativeEncoder = turnSparkMax.getEncoder();

    driveSparkMax.restoreFactoryDefaults();
    turnSparkMax.restoreFactoryDefaults();
    driveSparkMax.setCANTimeout(250);
    turnSparkMax.setCANTimeout(250);

    for (int i = 0; i < 4; i++) {
      turnSparkMax.setInverted(config.turnMotorInverted());
      driveSparkMax.setSmartCurrentLimit(40);
      turnSparkMax.setSmartCurrentLimit(30);
      driveSparkMax.enableVoltageCompensation(12.0);
      turnSparkMax.enableVoltageCompensation(12.0);

      driveEncoder.setPosition(0.0);
      driveEncoder.setMeasurementPeriod(10);
      driveEncoder.setAverageDepth(2);
      driveEncoder.setPositionConversionFactor(2 * Math.PI / moduleConstants.driveReduction());
      driveEncoder.setVelocityConversionFactor(
          2 * Math.PI / (60.0 * moduleConstants.driveReduction()));

      turnRelativeEncoder.setPosition(0.0);
      turnRelativeEncoder.setMeasurementPeriod(10);
      turnRelativeEncoder.setAverageDepth(2);
      turnRelativeEncoder.setPositionConversionFactor(
          2 * Math.PI / moduleConstants.turnReduction());
      turnRelativeEncoder.setVelocityConversionFactor(
          2 * Math.PI / (60.0 * moduleConstants.turnReduction()));

      driveSparkMax.setCANTimeout(0);
      turnSparkMax.setCANTimeout(0);

      driveSparkMax.setPeriodicFramePeriod(
          PeriodicFrame.kStatus2, (int) (1000.0 / odometryFrequency));
      turnSparkMax.setPeriodicFramePeriod(
          PeriodicFrame.kStatus2, (int) (1000.0 / odometryFrequency));

      if (driveSparkMax.burnFlash().equals(REVLibError.kOk)
          && turnSparkMax.burnFlash().equals(REVLibError.kOk)) break;
    }

    absoluteEncoderValue =
        () ->
            Rotation2d.fromRadians(
                    turnAbsoluteEncoder.getVoltage()
                        / RobotController.getVoltage5V()
                        * 2.0
                        * Math.PI)
                .minus(absoluteEncoderOffset);

    drivePositionQueue =
        SparkMaxOdometryThread.getInstance().registerSignal(driveEncoder::getPosition);
    turnPositionQueue =
        SparkMaxOdometryThread.getInstance()
            .registerSignal(() -> absoluteEncoderValue.get().getRadians());

    driveFeedforward = new SimpleMotorFeedforward(moduleConstants.ffKs(), moduleConstants.ffKv());
    driveFeedback = new PIDController(moduleConstants.driveKp(), 0.0, moduleConstants.drivekD());
    turnFeedback = new PIDController(moduleConstants.turnKp(), 0.0, moduleConstants.turnkD());
    turnFeedback.enableContinuousInput(-Math.PI, Math.PI);
  }

  @Override
  public void updateInputs(ModuleIOInputs inputs) {
    inputs.drivePositionRad = driveEncoder.getPosition();
    inputs.driveVelocityRadPerSec = driveEncoder.getVelocity();
    inputs.driveAppliedVolts = driveSparkMax.getAppliedOutput() * driveSparkMax.getBusVoltage();
    inputs.driveCurrentAmps = new double[] {driveSparkMax.getOutputCurrent()};

    inputs.turnAbsolutePosition = absoluteEncoderValue.get();
    inputs.turnPosition = Rotation2d.fromRadians(turnRelativeEncoder.getPosition());
    inputs.turnVelocityRadPerSec = turnRelativeEncoder.getVelocity();
    inputs.turnAppliedVolts = turnSparkMax.getAppliedOutput() * turnSparkMax.getBusVoltage();
    inputs.turnCurrentAmps = new double[] {turnSparkMax.getOutputCurrent()};

    inputs.odometryDrivePositionsMeters =
        drivePositionQueue.stream().mapToDouble(rads -> rads * wheelRadius).toArray();
    inputs.odometryTurnPositions =
        turnPositionQueue.stream().map(Rotation2d::fromRadians).toArray(Rotation2d[]::new);
    drivePositionQueue.clear();
    turnPositionQueue.clear();
  }

  @Override
  public void periodic() {
    // Reset to absolute encoder if abs encoder is non zero
    if (++currentIteration >= reSeedIterations && absoluteEncoderValue.get().getRadians() != 0.0) {
      REVLibError error =
          turnRelativeEncoder.setPosition(
              (turnAbsoluteEncoder.getVoltage() / RobotController.getVoltage5V())
                  * moduleConstants.turnReduction());
      // Reset current iteration to 0 if reset went through
      if (error == REVLibError.kOk) currentIteration = 0;
    }

    // Run closed loop turn control
    if (angleSetpoint != null) {
      turnSparkMax.setVoltage(
          turnFeedback.calculate(
              absoluteEncoderValue.get().getRadians(), angleSetpoint.getRadians()));
      // Run closed loop drive control
      if (speedSetpoint != null) {
        // Scale velocity based on turn error
        double adjustSpeedSetpoint = speedSetpoint * Math.cos(turnFeedback.getPositionError());
        double velocityRadPerSec = adjustSpeedSetpoint / wheelRadius;
        driveSparkMax.setVoltage(
            driveFeedforward.calculate(velocityRadPerSec)
                + driveFeedback.calculate(driveEncoder.getVelocity(), velocityRadPerSec));
      }
    }
  }

  @Override
  public void runSetpoint(SwerveModuleState state) {
    angleSetpoint = state.angle;
    speedSetpoint = state.speedMetersPerSecond;
  }

  @Override
  public void runCharacterization(double volts) {
    angleSetpoint = new Rotation2d();
    speedSetpoint = null;

    driveSparkMax.setVoltage(volts);
  }

  @Override
  public void setDriveBrakeMode(boolean enable) {
    driveSparkMax.setIdleMode(enable ? IdleMode.kBrake : IdleMode.kCoast);
  }

  @Override
  public void setTurnBrakeMode(boolean enable) {
    turnSparkMax.setIdleMode(enable ? IdleMode.kBrake : IdleMode.kCoast);
  }

  @Override
  public void stop() {
    driveSparkMax.setVoltage(0.0);
    turnSparkMax.setVoltage(0.0);

    speedSetpoint = null;
    angleSetpoint = null;
  }

  @Override
  public Rotation2d getAngle() {
    return absoluteEncoderValue.get();
  }

  @Override
  public double getPositionMeters() {
    return driveEncoder.getPosition() * wheelRadius;
  }

  @Override
  public double getVelocityMetersPerSec() {
    return driveEncoder.getVelocity() * wheelRadius;
  }

  @Override
  public double getCharacterizationVelocity() {
    return driveEncoder.getVelocity();
  }
}
