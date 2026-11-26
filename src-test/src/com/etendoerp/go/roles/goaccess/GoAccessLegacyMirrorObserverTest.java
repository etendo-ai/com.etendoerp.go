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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.application.Process;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.util.GoFeature;

/**
 * Drives the DAL observer with mocked persistence events: a grant, a downgrade, a deactivation, a
 * revoke, a grant moved to another role or window, and a grant on an unrelated window.
 *
 * @covers com.etendoerp.go.roles.goaccess.GoAccessLegacyMirrorObserver
 */
class GoAccessLegacyMirrorObserverTest {

  private static final String WINDOW_FEATURE = GoFeature.FISCAL_MODELS.legacyId;
  private static final String PROCESS_FEATURE = GoFeature.NOT_POSTED_DOCUMENTS.legacyId;

  private final GoAccessLegacyMirrorObserver observer = new GoAccessLegacyMirrorObserver();
  private final Map<String, Property> properties = new HashMap<>();
  private MockedStatic<ModelProvider> modelProvider;
  private MockedStatic<TriggerHandler> triggerHandler;
  private MockedStatic<GoAccessLegacyMirror> mirror;
  private Entity windowEntity;
  private Entity processEntity;

  @BeforeEach
  void setUp() throws Exception {
    windowEntity = entity();
    processEntity = entity();
    ModelProvider instance = mock(ModelProvider.class);
    when(instance.getEntity(WindowAccess.ENTITY_NAME)).thenReturn(windowEntity);
    when(instance.getEntity(ProcessAccess.ENTITY_NAME)).thenReturn(processEntity);
    modelProvider = mockStatic(ModelProvider.class);
    modelProvider.when(ModelProvider::getInstance).thenReturn(instance);
    TriggerHandler trigger = mock(TriggerHandler.class);
    when(trigger.isDisabled()).thenReturn(false);
    triggerHandler = mockStatic(TriggerHandler.class);
    triggerHandler.when(TriggerHandler::getInstance).thenReturn(trigger);
    mirror = mockStatic(GoAccessLegacyMirror.class);
  }

  @AfterEach
  void tearDown() {
    mirror.close();
    triggerHandler.close();
    modelProvider.close();
  }

  @Test
  void aNewReadWriteGrantIsMirroredAsActiveReadWrite() {
    WindowAccess access = windowAccess("R1", WINDOW_FEATURE, "A1");

    observer.onSave(newEvent(access, true, true));

    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange("R1", WINDOW_FEATURE, true, true,
        "A1"));
  }

  @Test
  void aDowngradeToReadOnlyIsMirroredAsReadOnly() {
    WindowAccess access = windowAccess("R1", WINDOW_FEATURE, "A1");
    EntityUpdateEvent event = updateEvent(access, true, false);

    observer.onUpdate(event);

    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange("R1", WINDOW_FEATURE, true,
        false, "A1"));
  }

  @Test
  void aResaveThatChangesNeitherTierNorRoleNorWindowLeavesTheGoRowUntouched() {
    WindowAccess access = windowAccess("R1", WINDOW_FEATURE, "A1");
    EntityUpdateEvent event = updateEvent(access, true, true);
    when(event.getPreviousState(properties.get("active"))).thenReturn(true);
    when(event.getPreviousState(properties.get("editableField"))).thenReturn(true);
    when(event.getPreviousState(properties.get("role"))).thenReturn(access.getRole());
    when(event.getPreviousState(properties.get("window"))).thenReturn(access.getWindow());
    when(event.getCurrentState(properties.get("role"))).thenReturn(access.getRole());
    when(event.getCurrentState(properties.get("window"))).thenReturn(access.getWindow());

    observer.onUpdate(event);

    mirror.verify(
        () -> GoAccessLegacyMirror.onClassicGrantChange(anyString(), anyString(), anyBoolean(),
            anyBoolean(), anyString()),
        never());
  }

  @Test
  void aDeactivatedGrantIsMirroredAsInactive() {
    WindowAccess access = windowAccess("R1", WINDOW_FEATURE, "A1");

    observer.onUpdate(updateEvent(access, false, true));

    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange("R1", WINDOW_FEATURE, false,
        true, "A1"));
  }

  @Test
  void aDeletedGrantIsMirroredAsInactiveWhateverItsFlagsWere() {
    WindowAccess access = windowAccess("R1", WINDOW_FEATURE, "A1");
    EntityDeleteEvent event = mock(EntityDeleteEvent.class);
    when(event.getTargetInstance()).thenReturn(access);
    when(event.getCurrentState(properties.get("active"))).thenReturn(true);
    when(event.getCurrentState(properties.get("editableField"))).thenReturn(true);

    observer.onDelete(event);

    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange("R1", WINDOW_FEATURE, false,
        true, "A1"));
  }

  @Test
  void aProcessGrantOfTheNotPostedFeatureIsMirrored() {
    ProcessAccess access = mock(ProcessAccess.class);
    Process process = mock(Process.class);
    when(process.getId()).thenReturn(PROCESS_FEATURE);
    Role role = role("R2");
    when(access.getRole()).thenReturn(role);
    when(access.getObuiappProcess()).thenReturn(process);
    when(access.getId()).thenReturn("P1");
    when(access.getEntity()).thenReturn(processEntity);
    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(access);
    when(event.getCurrentState(properties.get("active"))).thenReturn(true);
    when(event.getCurrentState(properties.get("editableField"))).thenReturn(false);

    observer.onSave(event);

    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange("R2", PROCESS_FEATURE, true,
        false, "P1"));
  }

  @Test
  void aGrantOnAnUnrelatedWindowIsIgnored() {
    WindowAccess access = windowAccess("R1", "SOME_OTHER_WINDOW", "A1");

    observer.onSave(newEvent(access, true, true));
    observer.onUpdate(updateEvent(access, true, true));

    mirror.verify(
        () -> GoAccessLegacyMirror.onClassicGrantChange(anyString(), anyString(), anyBoolean(),
            anyBoolean(), anyString()),
        never());
  }

  @Test
  void aGrantMovedToAnotherRoleResyncsTheRoleItLeft() {
    WindowAccess access = windowAccess("R2", WINDOW_FEATURE, "A1");
    EntityUpdateEvent event = updateEvent(access, true, true);
    Role currentRole = access.getRole();
    Window currentWindow = access.getWindow();
    Role left = role("R1");
    when(event.getPreviousState(properties.get("role"))).thenReturn(left);
    when(event.getPreviousState(properties.get("window"))).thenReturn(currentWindow);
    when(event.getCurrentState(properties.get("role"))).thenReturn(currentRole);
    when(event.getCurrentState(properties.get("window"))).thenReturn(currentWindow);

    observer.onUpdate(event);

    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange("R2", WINDOW_FEATURE, true,
        true, "A1"));
    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange("R1", WINDOW_FEATURE, false,
        false, "A1"));
  }

  @Test
  void aGrantMovedToAnotherWindowResyncsTheFeatureItLeft() {
    WindowAccess access = windowAccess("R1", "SOME_OTHER_WINDOW", "A1");
    Window previous = window(WINDOW_FEATURE);
    EntityUpdateEvent event = updateEvent(access, true, true);
    Role currentRole = access.getRole();
    Window currentWindow = access.getWindow();
    when(event.getPreviousState(properties.get("role"))).thenReturn(currentRole);
    when(event.getPreviousState(properties.get("window"))).thenReturn(previous);
    when(event.getCurrentState(properties.get("role"))).thenReturn(currentRole);
    when(event.getCurrentState(properties.get("window"))).thenReturn(currentWindow);

    observer.onUpdate(event);

    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange("R1", WINDOW_FEATURE, false,
        false, "A1"));
    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange(eq("R1"),
        eq("SOME_OTHER_WINDOW"), anyBoolean(), anyBoolean(), anyString()), never());
  }

  @Test
  void anUpdateThatKeepsRoleAndWindowDoesNotResyncAnOldPair() {
    WindowAccess access = windowAccess("R1", WINDOW_FEATURE, "A1");
    EntityUpdateEvent event = updateEvent(access, true, true);
    Role currentRole = access.getRole();
    Window currentWindow = access.getWindow();
    when(event.getPreviousState(properties.get("role"))).thenReturn(currentRole);
    when(event.getPreviousState(properties.get("window"))).thenReturn(currentWindow);
    when(event.getCurrentState(properties.get("role"))).thenReturn(currentRole);
    when(event.getCurrentState(properties.get("window"))).thenReturn(currentWindow);

    observer.onUpdate(event);

    mirror.verify(() -> GoAccessLegacyMirror.onClassicGrantChange(any(), any(), eq(false),
        eq(false), any()), never());
  }

  private Entity entity() {
    Entity entity = mock(Entity.class);
    for (String name : new String[] { "active", "editableField", "role", "window",
        "obuiappProcess" }) {
      Property property = properties.computeIfAbsent(name, k -> mock(Property.class));
      when(entity.getProperty(name)).thenReturn(property);
    }
    return entity;
  }

  private static Role role(String id) {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(id);
    return role;
  }

  private static Window window(String id) {
    Window window = mock(Window.class);
    when(window.getId()).thenReturn(id);
    return window;
  }

  private WindowAccess windowAccess(String roleId, String windowId, String id) {
    WindowAccess access = mock(WindowAccess.class);
    Role role = role(roleId);
    Window window = window(windowId);
    when(access.getRole()).thenReturn(role);
    when(access.getWindow()).thenReturn(window);
    when(access.getId()).thenReturn(id);
    when(access.getEntity()).thenReturn(windowEntity);
    return access;
  }

  private EntityNewEvent newEvent(WindowAccess access, boolean active, boolean readWrite) {
    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(access);
    when(event.getCurrentState(properties.get("active"))).thenReturn(active);
    when(event.getCurrentState(properties.get("editableField"))).thenReturn(readWrite);
    return event;
  }

  private EntityUpdateEvent updateEvent(WindowAccess access, boolean active, boolean readWrite) {
    EntityUpdateEvent event = mock(EntityUpdateEvent.class);
    when(event.getTargetInstance()).thenReturn(access);
    when(event.getCurrentState(properties.get("active"))).thenReturn(active);
    when(event.getCurrentState(properties.get("editableField"))).thenReturn(readWrite);
    return event;
  }
}
