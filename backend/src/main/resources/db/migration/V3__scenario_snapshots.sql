-- Закрепляет нормализованную конфигурацию preset/custom-сценария за одной тренировкой.
ALTER TABLE chat_sessions
    ADD COLUMN scenario_snapshot JSONB NULL;
