/**
 * Minimal `node:sqlite` compatibility layer backed by sql.js.
 *
 * The boardgame-web-ts core module only needs `DatabaseSync` with
 * `exec()`, `prepare()`, `StatementSync.all()`, `get()` and `run()`.
 */
import fs from 'node:fs';
import path from 'node:path';

const sqlJs = globalThis.__BOARDGAME_SQL_JS__;

if (!sqlJs) {
    throw new Error('sql.js must be initialised before node:sqlite is imported.');
}

function persistDatabase(database, filename) {
    if (!filename || filename === ':memory:') {
        return;
    }

    const bytes = database.export();
    const directory = path.dirname(filename);
    const temporaryPath = `${filename}.tmp`;

    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(temporaryPath, bytes);
    fs.renameSync(temporaryPath, filename);
}

export class DatabaseSync {
    #database;
    #filename;

    constructor(filename = ':memory:', _options = {}) {
        this.#filename = filename;

        if (filename !== ':memory:' && fs.existsSync(filename)) {
            this.#database = new sqlJs.Database(fs.readFileSync(filename));
        } else {
            this.#database = new sqlJs.Database();
        }
    }

    exec(sql) {
        this.#database.exec(sql);
        this._persist();
    }

    prepare(sql) {
        return new StatementSync(this, sql);
    }

    close() {
        this._persist();
        this.#database.close();
    }

    _prepare(sql) {
        return this.#database.prepare(sql);
    }

    _rowsModified() {
        return this.#database.getRowsModified();
    }

    _lastInsertRowid() {
        const result = this.#database.exec('SELECT last_insert_rowid() AS id');
        return Number(result[0]?.values[0]?.[0] ?? 0);
    }

    _persist() {
        persistDatabase(this.#database, this.#filename);
    }
}

export class StatementSync {
    #database;
    #statement;
    #sql;

    constructor(database, sql) {
        this.#database = database;
        this.#sql = sql;
        this.#statement = database._prepare(sql);
    }

    #resetAndBind(parameters) {
        this.#statement.reset();

        if (parameters.length > 0) {
            this.#statement.bind(parameters);
        }
    }

    all(...parameters) {
        this.#resetAndBind(parameters);

        const rows = [];
        while (this.#statement.step()) {
            rows.push(this.#statement.getAsObject());
        }
        this.#statement.reset();

        return rows;
    }

    get(...parameters) {
        this.#resetAndBind(parameters);

        const row = this.#statement.step()
            ? this.#statement.getAsObject()
            : undefined;

        this.#statement.reset();
        return row;
    }

    run(...parameters) {
        this.#resetAndBind(parameters);
        this.#statement.step();
        this.#statement.reset();

        const changes = this.#database._rowsModified();
        const lastInsertRowid = this.#database._lastInsertRowid();

        // sql.js `export()` closes and reopens the database, which invalidates
        // prepared statements. Re-prepare so the statement remains usable.
        this.#database._persist();
        this.#statement = this.#database._prepare(this.#sql);

        return {
            changes,
            lastInsertRowid
        };
    }
}
