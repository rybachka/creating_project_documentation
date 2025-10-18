from pydantic import BaseModel, Field
from typing import List, Optional

class Param(BaseModel):
    name: str
    type: Optional[str] = None
    description: Optional[str] = None

class ReturnMeta(BaseModel):
    type: Optional[str] = None
    description: Optional[str] = None

class DescribeIn(BaseModel):
    symbol: str                      # np. UsersController.getById
    kind: Optional[str] = "endpoint" # "endpoint"/"function"
    signature: Optional[str] = None  # np. GET /api/users/{id}: UserResponse
    comment: Optional[str] = None    # Javadoc/Opis z adnotacji/komentarz surowy
    params: List[Param] = []
    returns: Optional[ReturnMeta] = None

class ParamDoc(BaseModel):
    name: str
    doc: str

class DescribeOut(BaseModel):
    shortDescription: str
    mediumDescription: str
    longDescription: str
    paramDocs: List[ParamDoc] = []
    returnDoc: Optional[str] = None
