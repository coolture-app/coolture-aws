FROM public.ecr.aws/lambda/python:3.13

ARG TORCH_VERSION=2.12.0
ARG EMBEDDING_MODEL=all-mpnet-base-v2

COPY src/requirements.txt ${LAMBDA_TASK_ROOT}/
RUN pip install --no-cache-dir torch==${TORCH_VERSION} --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir -r ${LAMBDA_TASK_ROOT}/requirements.txt

RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('${EMBEDDING_MODEL}').save('/opt/model')" \
 && chmod -R a+rX /opt/model \
 && rm -rf /root/.cache

ENV EMBEDDING_MODEL=${EMBEDDING_MODEL} \
    EMBEDDING_MODEL_DIR=/opt/model \
    HF_HUB_OFFLINE=1 \
    HF_HOME=/tmp/hf \
    TOKENIZERS_PARALLELISM=false

COPY src/app.py src/models.py src/settings.py ${LAMBDA_TASK_ROOT}/

CMD ["app.lambda_handler"]
