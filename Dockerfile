FROM python:3.12-slim

# نصب پکیج‌های tools.json از قبل، تا run_python دسترسی به pip install نداشته باشد
RUN pip install --no-cache-dir numpy requests pytest \
 && pip install --no-cache-dir flask openai pillow cachetools

WORKDIR /app
COPY app.py tools.json ./
COPY static ./static

# اجرا با کاربر غیر-root
RUN useradd -m appuser && chown -R appuser:appuser /app
USER appuser

ENV PYTHONUNBUFFERED=1
EXPOSE 5000
CMD ["python", "app.py"]
