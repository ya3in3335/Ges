-- Census: number of children, asked when the person is married (the wife is counted with the children).
-- Additive only. member_profiles already has children_count (shared with the lectures form).
ALTER TABLE stats_registrations ADD COLUMN children_count INTEGER CHECK (children_count IS NULL OR children_count BETWEEN 0 AND 30);
