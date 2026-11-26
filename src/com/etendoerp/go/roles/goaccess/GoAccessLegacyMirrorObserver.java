/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.roles.goaccess;

import java.util.Objects;

import javax.enterprise.event.Observes;

import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.application.Process;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.util.GoFeature;

/**
 * Mirrors a classic window/OBUIAPP process grant onto the role's GO Access row when the grant is
 * one of the six legacy records of a {@link GoFeature} (ETP-4802). It covers every write that goes
 * through the DAL: the Role window, the template injectors, role composition and the roles-matrix
 * editor. See {@link GoAccessLegacyMirror} for the rule and for the native writers.
 *
 * <p>It declares no priority: it relies on reading the row's state from the event, which the
 * overlap guards update in place before this observer's query runs.</p>
 */
public class GoAccessLegacyMirrorObserver extends EntityPersistenceEventObserver {

  private static final String ROLE_PROPERTY = "role";
  private static final String WINDOW_PROPERTY = "window";
  private static final String PROCESS_PROPERTY = "obuiappProcess";
  private static final String ACTIVE_PROPERTY = "active";
  private static final String EDITABLE_PROPERTY = "editableField";

  private Entity[] entities;

  @Override
  protected Entity[] getObservedEntities() {
    if (entities == null) {
      entities = new Entity[] { ModelProvider.getInstance().getEntity(WindowAccess.ENTITY_NAME),
          ModelProvider.getInstance().getEntity(ProcessAccess.ENTITY_NAME) };
    }
    return entities;
  }

  /**
   * Handles a new grant.
   *
   * @param event the persistence event
   */
  public void onSave(@Observes EntityNewEvent event) {
    handle(event, false);
  }

  /**
   * Handles a changed grant (tier, active flag, or record).
   *
   * @param event the persistence event
   */
  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event) || !grantChanged(event)) {
      return;
    }
    handle(event, false);
    handlePreviousPair(event);
  }

  /**
   * A re-save that changes none of the tier, the active flag, the role or the record is not a
   * classic grant change: an administrator's direct GO Access edit must stand through it.
   */
  private boolean grantChanged(EntityUpdateEvent event) {
    Entity entity = event.getTargetInstance().getEntity();
    String recordProperty = event.getTargetInstance() instanceof WindowAccess ? WINDOW_PROPERTY
        : PROCESS_PROPERTY;
    for (String name : new String[] { ACTIVE_PROPERTY, EDITABLE_PROPERTY, ROLE_PROPERTY, recordProperty }) {
      if (!Objects.equals(event.getPreviousState(entity.getProperty(name)),
          event.getCurrentState(entity.getProperty(name)))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Handles a revoked grant.
   *
   * @param event the persistence event
   */
  public void onDelete(@Observes EntityDeleteEvent event) {
    handle(event, true);
  }

  private void handle(EntityPersistenceEvent event, boolean deleted) {
    if (!isValidEvent(event)) {
      return;
    }
    BaseOBObject target = event.getTargetInstance();
    Role role;
    String legacyId;
    if (target instanceof WindowAccess) {
      WindowAccess access = (WindowAccess) target;
      role = access.getRole();
      Window window = access.getWindow();
      legacyId = window == null ? null : window.getId();
    } else if (target instanceof ProcessAccess) {
      ProcessAccess access = (ProcessAccess) target;
      role = access.getRole();
      Process process = access.getObuiappProcess();
      legacyId = process == null ? null : process.getId();
    } else {
      return;
    }
    if (role == null || GoFeature.forLegacyId(legacyId) == null) {
      return;
    }
    Entity entity = target.getEntity();
    boolean active = !deleted
        && Boolean.TRUE.equals(event.getCurrentState(entity.getProperty(ACTIVE_PROPERTY)));
    boolean readWrite = Boolean.TRUE
        .equals(event.getCurrentState(entity.getProperty(EDITABLE_PROPERTY)));
    GoAccessLegacyMirror.onClassicGrantChange(role.getId(), legacyId, active, readWrite,
        (String) target.getId());
  }

  /**
   * When an update moves a grant to another role or record, the pair it left no longer has this
   * row: resync it without this row so a stale GO Access row cannot outlive the grant.
   */
  private void handlePreviousPair(EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    BaseOBObject target = event.getTargetInstance();
    Entity entity = target.getEntity();
    boolean isWindow = target instanceof WindowAccess;
    if (!isWindow && !(target instanceof ProcessAccess)) {
      return;
    }
    Object previousRole = event.getPreviousState(entity.getProperty(ROLE_PROPERTY));
    Object previousRecord = event
        .getPreviousState(entity.getProperty(isWindow ? WINDOW_PROPERTY : PROCESS_PROPERTY));
    Object currentRole = event.getCurrentState(entity.getProperty(ROLE_PROPERTY));
    Object currentRecord = event
        .getCurrentState(entity.getProperty(isWindow ? WINDOW_PROPERTY : PROCESS_PROPERTY));
    if (previousRole instanceof Role && previousRecord instanceof BaseOBObject
        && (!previousRole.equals(currentRole) || !previousRecord.equals(currentRecord))) {
      GoAccessLegacyMirror.onClassicGrantChange(((Role) previousRole).getId(),
          (String) ((BaseOBObject) previousRecord).getId(), false, false, (String) target.getId());
    }
  }
}
