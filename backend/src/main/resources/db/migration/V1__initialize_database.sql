-- Infrastructure only. Business tables belong to later phases.
-- Required by the future room/date exclusion constraint.
CREATE EXTENSION IF NOT EXISTS btree_gist;
