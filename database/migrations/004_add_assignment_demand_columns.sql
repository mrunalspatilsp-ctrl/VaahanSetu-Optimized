-- Migration 004: Add peak_demand and occupancy_percent to allocation_assignments
ALTER TABLE allocation_assignments ADD COLUMN IF NOT EXISTS peak_demand INTEGER NULL DEFAULT 0;
ALTER TABLE allocation_assignments ADD COLUMN IF NOT EXISTS occupancy_percent INTEGER NULL DEFAULT 0;
