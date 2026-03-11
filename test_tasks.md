# Test Tasks: TodoFlow — a Todoist-like Task Manager

A web application with a React frontend and a Python (FastAPI) backend.
Users can manage projects, create tasks with priorities and due dates, and track progress.

Tech stack: FastAPI + SQLite (backend), React + TypeScript + Vite (frontend).

---

## Task 1: Backend project scaffold

Set up a FastAPI project with SQLite database via SQLAlchemy.
Create the project structure:
- `backend/main.py` — FastAPI app entrypoint
- `backend/database.py` — SQLAlchemy engine, session, Base
- `backend/requirements.txt` — fastapi, uvicorn, sqlalchemy, pydantic

The app should start with `uvicorn main:app` and return `{"status": "ok"}` on `GET /health`.

---

## Task 2: Database models

Create SQLAlchemy models in `backend/models.py`:
- **Project**: id, name, color (hex string), created_at
- **Task**: id, project_id (FK to Project), title, description (nullable), priority (1-4, default 4), due_date (nullable), is_completed (bool, default false), created_at, completed_at (nullable)

Add Alembic or auto-create tables on startup.

---

## Task 3: Projects CRUD API

Implement REST endpoints in `backend/routers/projects.py`:
- `POST /api/projects` — create project (name, color)
- `GET /api/projects` — list all projects with task counts
- `GET /api/projects/{id}` — get project with its tasks
- `PATCH /api/projects/{id}` — update project name/color
- `DELETE /api/projects/{id}` — delete project and its tasks

Use Pydantic schemas for request/response validation.

---

## Task 4: Tasks CRUD API

Implement REST endpoints in `backend/routers/tasks.py`:
- `POST /api/tasks` — create task (title, project_id, priority, due_date, description)
- `GET /api/tasks` — list tasks with filtering: by project_id, by is_completed, by priority
- `GET /api/tasks/{id}` — get single task
- `PATCH /api/tasks/{id}` — update task fields
- `DELETE /api/tasks/{id}` — delete task
- `POST /api/tasks/{id}/complete` — mark task as completed (set is_completed=true, completed_at=now)
- `POST /api/tasks/{id}/reopen` — reopen completed task

---

## Task 5: "Today" and "Upcoming" endpoints

Add special query endpoints:
- `GET /api/tasks/today` — tasks with due_date = today, not completed
- `GET /api/tasks/upcoming` — tasks with due_date in the next 7 days, not completed, sorted by due_date

These are the core views a user sees when opening the app.

---

## Task 6: Frontend project scaffold

Set up a React + TypeScript + Vite project in `frontend/`.
Install dependencies: react-router-dom, axios (or fetch wrapper).
Create the basic layout:
- Sidebar with navigation (Inbox/Today/Upcoming/Projects list)
- Main content area
- Use CSS modules or plain CSS, no heavy UI libraries

The app should build and show the layout shell with placeholder content.

---

## Task 7: Projects UI

Implement project management in the frontend:
- Sidebar shows list of projects fetched from API, each with its color dot
- "Add Project" button opens a form (name + color picker)
- Clicking a project shows its tasks in the main area
- Project settings: rename, change color, delete (with confirmation)

---

## Task 8: Task list and creation UI

Implement the task list view:
- Show tasks for the selected project (or all tasks for Inbox)
- Each task shows: checkbox, title, priority indicator (colored flag), due date
- Clicking checkbox calls complete/reopen API and updates UI
- "Add task" inline form at the bottom of the list: title input, priority selector (P1-P4), optional due date picker
- Completed tasks shown at the bottom in a collapsible "Completed" section

---

## Task 9: Task detail panel

When clicking a task title, show a detail side panel or modal:
- Editable title
- Editable description (textarea)
- Priority selector
- Due date picker
- Project selector (move task between projects)
- Delete button
- All changes saved via PATCH on blur or explicit save

---

## Task 10: Today and Upcoming views

Implement the two special views:
- **Today**: shows tasks due today grouped by project, with overdue tasks highlighted in red
- **Upcoming**: shows tasks for the next 7 days, grouped by date, each date as a section header
- Both views allow completing tasks inline (checkbox)
- "Add task" in Today view defaults due_date to today

---

## Task 11: Search and filter

Add a search bar in the top header:
- `GET /api/tasks/search?q=...` backend endpoint — searches by title substring (case-insensitive)
- Frontend: search input with debounced API calls, shows results in a dropdown or replaces main content
- Allow filtering by priority in any task list view (dropdown: All / P1 / P2 / P3 / P4)

---

## Task 12: Polish and integration

Final integration pass:
- Add CORS middleware to backend for frontend dev server
- Add loading states and error handling in frontend (toast notifications or inline errors)
- Empty states: show helpful messages when project has no tasks, or Today has nothing due
- Sort tasks: incomplete first, then by priority (P1 highest), then by due_date
- Ensure the app works end-to-end: start backend, start frontend, create project, add tasks, complete them, use Today/Upcoming views
