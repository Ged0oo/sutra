# 🧥 Sutra (سُترة) — Architecture & Execution Summary

## 1. 🧥 Project Idea: Sutra (سُترة)

**Sutra** is a modern, single-brand e-commerce platform specifically tailored for a clothing store. It is built to serve two primary user roles—Customers and a single Seller (Admin)—from a unified system. 

The application is deeply focused on providing a localized, premium user experience, featuring full bilingual support (Arabic/English) with RTL (Right-to-Left) layouts and dynamic Dark/Light themes. 

**Core capabilities include:**
* **Customer Journey:** Browsing a bilingual catalog, filtering by variants (size/color), managing active and guest shopping carts, saving wishlists, placing orders securely, tracking status via real-time notifications, and leaving verified reviews.
* **Seller Dashboard:** A centralized hub to manage products and inventory, process orders through a state machine, monitor stock alerts, and view real-time data analytics and charts.

---

## 2. 💻 Used Technologies

The system is designed as a **Service-Oriented Monolith**, keeping deployment simple while maintaining strict internal boundaries.

### Backend (The Monolith)
* **Framework:** Java with Spring Boot
* **Security:** Spring Security with JWT (Access & Refresh tokens)
* **Real-Time:** Spring WebSocket (STOMP over SockJS) for in-app notifications

### Frontend (Single Page Application)
* **Framework:** Angular (Lazy-loaded modules separating Customer and Seller routes)
* **Visualizations:** Chart.js for the Seller analytics dashboard
* **Styling:** Native CSS custom properties for theming and logical properties for RTL support

### Data & Infrastructure
* **Primary Database:** MongoDB (Atlas) — Chosen for its flexible document schema, perfect for storing product variants, bilingual content, and immutable order snapshots.
* **Cache & Transient Data:** Redis — Used for high-speed cart operations, session token blacklisting, rate limiting, and aggressive read-caching.
* **External Services:** * **Stripe:** Secure payment processing and webhook handling.
  * **Cloudinary / S3:** Image hosting and on-the-fly optimization.
  * **SendGrid / SMTP:** Automated email dispatch.
* **DevOps:** Docker, NGINX (Reverse Proxy & SSL termination), Let's Encrypt, and CI/CD pipelines (GitHub Actions/GitLab CI).

---

## 3. 🚀 Key Advantages (Pros)

The architectural choices made in this blueprint offer several distinct benefits for a small team or solo developer building a robust e-commerce platform:

* **Development Speed & Simplicity:** By choosing a Monolith over Microservices, the team avoids the heavy operational overhead of distributed systems (like network latency, complex sagas, and service discovery) while still keeping code organized in isolated internal modules.
* **Future-Proof Modularity:** Because internal modules communicate via strict interfaces (e.g., `OrderService` calling `CartService`), the codebase is primed to be broken out into microservices later if traffic or team size demands it.
* **High Performance:** Redis acts as a critical buffer. By storing highly volatile data (like active shopping carts) and heavily requested data (like catalog reads) in memory, the load on the primary MongoDB database is drastically reduced.
* **Data Integrity & Reliability:** * **Immutability:** Orders snapshot the exact product price, details, and shipping address at the time of checkout, ensuring historical accuracy even if the product changes later.
  * **Atomic Operations:** MongoDB's atomic operators (`$inc`) are used for inventory management to prevent race conditions and overselling during simultaneous checkouts.
* **Native Localization:** The data models are built from the ground up to support `{en, ar}` objects, eliminating the need for complex translation joins in the database.
* **Actionable Seller Insights:** The inclusion of a dedicated analytics module that pre-computes daily, weekly, and monthly data snapshots ensures the Seller dashboard loads instantly without running expensive database queries on the fly.
